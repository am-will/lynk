import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class RealtimeVoiceController {
    enum Status: String, Sendable {
        case idle = "Idle"
        case connecting = "Connecting"
        case listening = "Listening"
        case thinking = "Thinking"
        case speaking = "Speaking"
        case error = "Error"
    }

    var status: Status = .idle
    var transcript = ""
    var isMuted = false
    var error: String?
    var currentTask: String?
    var queuedTasks = 0

    private let transportFactory: RealtimeVoiceTransportFactory
    private let permissionProvider: @MainActor () async -> Bool
    private let audioSessionConfigurator: @MainActor () throws -> Void
    private let audioSessionDeactivator: @MainActor () -> Void
    private let customWireSend: (@MainActor ([String: JSONValue]) async -> Bool)?
    private var transport: (any RealtimeVoiceTransport)?
    private weak var bridge: BridgeClient?
    private var generation = UUID()
    private var voiceSessionID: String?
    private var deviceID = ""
    private var selectedModel: String?
    private var reasoningEffort = "medium"
    private var toolAccumulator = RealtimeToolCallAccumulator()
    private var transcriptNormalizer = RealtimeTranscriptNormalizer()
    private var deliveredToolResults: Set<String> = []
    private var activeResponseID: String?
    private var cleanupComplete = true

    init(
        transportFactory: @escaping RealtimeVoiceTransportFactory = { WebRTCRealtimeTransport() },
        permissionProvider: @escaping @MainActor () async -> Bool = RealtimeVoiceController.systemMicrophonePermission,
        audioSessionConfigurator: @escaping @MainActor () throws -> Void = RealtimeVoiceController.configureSystemAudioSession,
        audioSessionDeactivator: @escaping @MainActor () -> Void = RealtimeVoiceController.deactivateSystemAudioSession,
        wireSend: (@MainActor ([String: JSONValue]) async -> Bool)? = nil
    ) {
        self.transportFactory = transportFactory
        self.permissionProvider = permissionProvider
        self.audioSessionConfigurator = audioSessionConfigurator
        self.audioSessionDeactivator = audioSessionDeactivator
        self.customWireSend = wireSend
    }

    var isActive: Bool { status != .idle && status != .error }

    func start(settings: SettingsSnapshot, chat: ChatStore, bridge: BridgeClient) async {
        guard !isActive else { return }
        guard bridge.phase == .registered else {
            status = .error
            error = "Connect to the Lynk bridge before starting voice."
            return
        }
        guard await permissionProvider() else {
            status = .error
            error = "Microphone permission is required for voice mode."
            return
        }
        let generation = UUID()
        let sessionID = UUID().uuidString.lowercased()
        self.generation = generation
        voiceSessionID = sessionID
        deviceID = settings.deviceID
        selectedModel = chat.selectedModel
        reasoningEffort = chat.reasoningEffort
        self.bridge = bridge
        cleanupComplete = false
        transcript = ""
        error = nil
        status = .connecting
        isMuted = false
        currentTask = nil
        queuedTasks = 0
        activeResponseID = nil
        deliveredToolResults.removeAll()
        toolAccumulator.reset()
        transcriptNormalizer.reset()
        do {
            try audioSessionConfigurator()
            let transport = try transportFactory()
            transport.onEvent = { [weak self] raw in self?.handleDataChannel(raw, generation: generation) }
            transport.onConnectionState = { [weak self] state in self?.handleConnectionState(state, generation: generation) }
            self.transport = transport
            let offer = try await transport.createOffer()
            guard owns(generation, sessionID: sessionID) else { return }
            var payload: [String: JSONValue] = [
                "type": .string("realtime.start"),
                "deviceId": .string(deviceID),
                "voiceSessionId": .string(sessionID),
                "sdp": .string(offer),
                "systemPrompt": .string(String(settings.systemPrompt.prefix(32_000))),
                "reasoningEffort": .string(reasoningEffort)
            ]
            if let selectedModel { payload["model"] = .string(selectedModel) }
            if Self.mayTransmitSecret(to: bridge.endpoint), !settings.openAIKey.isEmpty {
                payload["openAiApiKey"] = .string(settings.openAIKey)
            }
            guard await sendWire(payload) else {
                terminate(generation: generation, error: "Could not start realtime voice through the bridge.", sendStop: false)
                return
            }
        } catch {
            terminate(generation: generation, error: error.localizedDescription, sendStop: false)
        }
    }

    func receive(_ message: [String: JSONValue]) {
        guard let sessionID = message.string("voiceSessionId"), sessionID == voiceSessionID else { return }
        switch message.string("type") {
        case "realtime.sdp":
            guard let sdp = message.string("sdp"), let transport else { return }
            let ownedGeneration = generation
            Task {
                do {
                    try await transport.applyAnswer(sdp)
                    guard owns(ownedGeneration, sessionID: sessionID) else { return }
                    status = .listening
                    error = nil
                } catch {
                    terminate(generation: ownedGeneration, error: error.localizedDescription, sendStop: false)
                }
            }
        case "realtime.transcript_delta", "realtime.item_added", "realtime.speech_started":
            transcript = transcriptNormalizer.apply(message)
            status = message.string("role") == "user" ? .thinking : .speaking
        case "realtime.tool_result": handleToolResult(message)
        case "realtime.task_status":
            currentTask = message.string("currentTask")
            queuedTasks = max(0, Int(message.number("queued") ?? 0))
            if message.bool("running") == true { status = .thinking }
        case "realtime.error": terminate(generation: generation, error: message.string("message") ?? "Realtime voice failed.", sendStop: false)
        case "realtime.closed": terminate(generation: generation, error: message.string("reason"), sendStop: false, idle: true)
        default: break
        }
    }

    func toggleMute() {
        isMuted.toggle()
        transport?.setMuted(isMuted)
    }

    func stop(reason: String = "Stopped from Lynk iOS voice") {
        terminate(generation: generation, error: nil, sendStop: true, reason: reason, idle: true)
    }

    private func handleDataChannel(_ raw: String, generation: UUID) {
        guard owns(generation), let data = raw.data(using: .utf8),
              let event = try? JSONDecoder().decode([String: JSONValue].self, from: data) else { return }
        if let call = toolAccumulator.apply(event) {
            handleToolCall(call)
            return
        }
        let type = event.string("type") ?? ""
        trackResponse(type: type, event: event)
        if type == "input_audio_buffer.speech_started" {
            cancelResponseForBargeIn()
            transcript = transcriptNormalizer.apply(event)
            status = .listening
        } else if type.contains("transcript") || type == "conversation.item.created" || type.starts(with: "response.output_text.") {
            transcript = transcriptNormalizer.apply(event)
            status = type.contains("input_audio") ? .thinking : .speaking
        } else if type.contains("error") {
            let message = event.string("message") ?? event["error"]?.objectValue?.string("message") ?? "Realtime voice failed."
            if isBenignCancellation(message) { status = .listening; activeResponseID = nil }
            else { terminate(generation: generation, error: message, sendStop: false) }
        }
    }

    private func handleToolCall(_ call: RealtimeToolCall) {
        let forbidden = ["run_phone_task", "steer_phone_task", "stop_phone_task"]
        if forbidden.contains(call.name) {
            sendToolOutput(callID: call.callID, ok: false, status: "failed", output: nil, error: "Phone control is unavailable on iOS.", createResponse: true)
            return
        }
        guard let voiceSessionID else { return }
        var payload: [String: JSONValue] = [
            "type": .string("realtime.tool_call"),
            "deviceId": .string(deviceID),
            "voiceSessionId": .string(voiceSessionID),
            "callId": .string(call.callID),
            "name": .string(call.name),
            "reasoningEffort": .string(reasoningEffort),
            "arguments": .object(call.arguments)
        ]
        if let itemID = call.itemID { payload["itemId"] = .string(itemID) }
        if let selectedModel { payload["model"] = .string(selectedModel) }
        status = .thinking
        Task { _ = await sendWire(payload) }
    }

    private func handleToolResult(_ message: [String: JSONValue]) {
        guard let callID = message.string("callId"), deliveredToolResults.insert(callID).inserted else { return }
        sendToolOutput(
            callID: callID,
            ok: message.bool("ok") ?? false,
            status: message.string("status") ?? "failed",
            output: message.string("output"),
            error: message.string("error"),
            createResponse: message.bool("createResponse") ?? true
        )
    }

    private func sendToolOutput(callID: String, ok: Bool, status: String, output: String?, error: String?, createResponse: Bool) {
        var result: [String: JSONValue] = ["ok": .bool(ok), "status": .string(status)]
        if let output, !output.isEmpty { result["output"] = .string(output) }
        if let error, !error.isEmpty { result["error"] = .string(error) }
        let encoded = (try? JSONEncoder().encode(result)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let sent = transport?.send(event: [
            "type": .string("conversation.item.create"),
            "item": .object([
                "type": .string("function_call_output"),
                "call_id": .string(callID),
                "output": .string(encoded)
            ])
        ]) == true
        if sent, createResponse {
            _ = transport?.send(event: ["type": .string("response.create")])
            self.status = .thinking
        } else if sent { self.status = .listening }
        else { self.error = "Could not return the realtime tool result." }
    }

    private func trackResponse(type: String, event: [String: JSONValue]) {
        if type == "response.created" {
            activeResponseID = event["response"]?.objectValue?.string("id") ?? event.string("response_id")
        } else if type == "response.done" {
            let id = event["response"]?.objectValue?.string("id") ?? event.string("response_id")
            if id == nil || id == activeResponseID { activeResponseID = nil }
        }
    }

    private func cancelResponseForBargeIn() {
        guard let activeResponseID else { return }
        _ = transport?.send(event: ["type": .string("response.cancel"), "response_id": .string(activeResponseID)])
        _ = transport?.send(event: ["type": .string("output_audio_buffer.clear")])
        self.activeResponseID = nil
    }

    private func handleConnectionState(_ state: String, generation: UUID) {
        guard owns(generation) else { return }
        switch state {
        case "connected", "completed": status = .listening; error = nil
        case "failed", "disconnected": terminate(generation: generation, error: "WebRTC connection \(state).", sendStop: true)
        case "closed": terminate(generation: generation, error: nil, sendStop: false, idle: true)
        default: break
        }
    }

    private func terminate(
        generation: UUID,
        error: String?,
        sendStop: Bool,
        reason: String = "Voice session stopped",
        idle: Bool = false
    ) {
        guard owns(generation), !cleanupComplete else { return }
        cleanupComplete = true
        let sessionID = voiceSessionID
        transport?.close()
        transport = nil
        voiceSessionID = nil
        activeResponseID = nil
        audioSessionDeactivator()
        if sendStop, let sessionID {
            Task {
                _ = await sendWire([
                    "type": .string("realtime.stop"),
                    "deviceId": .string(deviceID),
                    "voiceSessionId": .string(sessionID),
                    "reason": .string(reason)
                ])
            }
        }
        self.error = error
        status = error == nil || idle ? .idle : .error
    }

    private func owns(_ generation: UUID, sessionID: String? = nil) -> Bool {
        self.generation == generation && !cleanupComplete && (sessionID == nil || voiceSessionID == sessionID)
    }

    private func sendWire(_ payload: [String: JSONValue]) async -> Bool {
        if let customWireSend { return await customWireSend(payload) }
        guard let bridge else { return false }
        return await bridge.send(payload)
    }

    private static func systemMicrophonePermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: true
        case .denied: false
        case .undetermined:
            await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
            }
        @unknown default: false
        }
    }

    private static func configureSystemAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
    }

    private static func deactivateSystemAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func isBenignCancellation(_ message: String) -> Bool {
        let value = message.lowercased()
        return (value.contains("response.cancel") || value.contains("cancellation failed"))
            && (value.contains("no active response") || value.contains("not active"))
    }

    static func mayTransmitSecret(to url: URL?) -> Bool {
        guard let url, let host = url.host?.lowercased() else { return false }
        return url.scheme == "wss" || host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}
