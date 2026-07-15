import Foundation
import Observation

@MainActor
@Observable
final class BridgeClient {
    enum Phase: Equatable {
        case disconnected
        case connecting
        case registered
        case reconnecting
        case failed(String)

        var label: String {
            switch self {
            case .disconnected: "Disconnected"
            case .connecting: "Connecting"
            case .registered: "Connected"
            case .reconnecting: "Reconnecting"
            case let .failed(message): message
            }
        }
    }

    var phase: Phase = .disconnected
    var endpoint: URL?
    private(set) var connectionGeneration = UUID()
    var onMessage: (([String: JSONValue]) -> Void)?

    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var snapshot: SettingsSnapshot?
    private var endpointIndex = 0
    private var reconnectAttempt = 0

    func connect(using snapshot: SettingsSnapshot) {
        disconnect(reconnect: false)
        self.snapshot = snapshot
        reconnectAttempt = 0
        endpointIndex = 0
        openNextEndpoint()
    }

    func retry() {
        guard let snapshot else { return }
        connect(using: snapshot)
    }

    func disconnect(reconnect: Bool = false) {
        watchdogTask?.cancel()
        reconnectTask?.cancel()
        receiveTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        phase = reconnect ? .reconnecting : .disconnected
    }

    @discardableResult
    func send(_ message: [String: JSONValue]) async -> Bool {
        guard phase == .registered, let socket else { return false }
        do {
            let data = try JSONEncoder().encode(message)
            guard let text = String(data: data, encoding: .utf8) else { return false }
            try await socket.send(.string(text))
            return true
        } catch {
            scheduleReconnect(reason: error.localizedDescription)
            return false
        }
    }

    private func openNextEndpoint() {
        guard let snapshot, !snapshot.token.isEmpty, !snapshot.deviceID.isEmpty else {
            phase = .failed("Pairing token and device ID are required")
            return
        }
        let endpoints = snapshot.bridgeURLs.compactMap { try? BridgeEndpoint($0) }
        guard !endpoints.isEmpty else {
            phase = .failed("Enter a valid ws:// or wss:// bridge URL")
            return
        }
        let selected = endpoints[endpointIndex % endpoints.count]
        endpointIndex += 1
        endpoint = selected.webSocketURL
        connectionGeneration = UUID()
        phase = reconnectAttempt == 0 ? .connecting : .reconnecting

        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = true
        let session = URLSession(configuration: configuration)
        let socket = session.webSocketTask(with: selected.webSocketURL)
        self.session = session
        self.socket = socket
        socket.resume()

        receiveTask = Task { [weak self, weak socket] in
            guard let self, let socket else { return }
            await self.receiveLoop(socket: socket)
        }
        Task { [weak self] in
            guard let self else { return }
            do {
                let payload = BridgeRegistration.payload(deviceID: snapshot.deviceID, token: snapshot.token)
                let data = try JSONEncoder().encode(payload)
                try await socket.send(.data(data))
                self.startRegistrationWatchdog(socket: socket)
            } catch {
                self.scheduleReconnect(reason: error.localizedDescription)
            }
        }
    }

    private func receiveLoop(socket: URLSessionWebSocketTask) async {
        while !Task.isCancelled, self.socket === socket {
            do {
                let frame = try await socket.receive()
                let data: Data
                switch frame {
                case let .data(value): data = value
                case let .string(value): data = Data(value.utf8)
                @unknown default: continue
                }
                let message = try JSONDecoder().decode([String: JSONValue].self, from: data)
                handle(message, socket: socket)
            } catch {
                if !Task.isCancelled { scheduleReconnect(reason: error.localizedDescription) }
                return
            }
        }
    }

    private func handle(_ message: [String: JSONValue], socket: URLSessionWebSocketTask) {
        guard self.socket === socket, let snapshot else { return }
        if BridgeRegistration.isAcknowledgement(message, deviceID: snapshot.deviceID) {
            watchdogTask?.cancel()
            reconnectAttempt = 0
            phase = .registered
            Task { [weak self] in
                _ = await self?.send([
                    "type": .string("chat.open"),
                    "deviceId": .string(snapshot.deviceID)
                ])
            }
            return
        }
        let type = message["type"]?.stringValue ?? ""
        if type == "command" || type == "command.cancel" {
            phase = .failed("Bridge attempted unsupported phone control")
            disconnect(reconnect: false)
            return
        }
        guard phase == .registered || type == "agent_status" else { return }
        onMessage?(message)
    }

    private func startRegistrationWatchdog(socket: URLSessionWebSocketTask) {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self, weak socket] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, let self, let socket, self.socket === socket, self.phase != .registered else { return }
            self.scheduleReconnect(reason: "Bridge registration timed out")
        }
    }

    private func scheduleReconnect(reason: String) {
        guard reconnectTask == nil else { return }
        watchdogTask?.cancel()
        receiveTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        phase = .reconnecting
        reconnectAttempt += 1
        let delay = min(pow(2, Double(reconnectAttempt - 1)), 15)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.reconnectTask = nil
            self.openNextEndpoint()
        }
        if reconnectAttempt > 8 { phase = .failed(reason) }
    }
}
