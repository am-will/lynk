import Foundation

@MainActor
protocol RealtimeVoiceTransport: AnyObject {
    var onEvent: ((String) -> Void)? { get set }
    var onConnectionState: ((String) -> Void)? { get set }

    func createOffer() async throws -> String
    func applyAnswer(_ sdp: String) async throws
    func setMuted(_ muted: Bool)
    func send(event: [String: JSONValue]) -> Bool
    func close()
}

typealias RealtimeVoiceTransportFactory = @MainActor () throws -> any RealtimeVoiceTransport

enum RealtimeVoiceTransportError: LocalizedError {
    case peerCreationFailed
    case offerFailed(String)
    case answerFailed(String)
    case noLocalDescription

    var errorDescription: String? {
        switch self {
        case .peerCreationFailed: "Could not create the WebRTC connection."
        case let .offerFailed(message): "Could not create the WebRTC offer: \(message)"
        case let .answerFailed(message): "Could not apply the WebRTC answer: \(message)"
        case .noLocalDescription: "WebRTC did not produce a local session description."
        }
    }
}
