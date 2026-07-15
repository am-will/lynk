import Foundation
import WebRTC

@MainActor
final class WebRTCRealtimeTransport: NSObject, RealtimeVoiceTransport {
    var onEvent: ((String) -> Void)?
    var onConnectionState: ((String) -> Void)?

    private let factory = RTCPeerConnectionFactory()
    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var dataChannel: RTCDataChannel?
    private var iceGatheringComplete = false

    func createOffer() async throws -> String {
        let configuration = RTCConfiguration()
        configuration.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        configuration.sdpSemantics = .unifiedPlan
        configuration.bundlePolicy = .maxBundle
        configuration.rtcpMuxPolicy = .require
        configuration.continualGatheringPolicy = .gatherOnce

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: self) else {
            throw RealtimeVoiceTransportError.peerCreationFailed
        }
        peerConnection = peer

        let source = factory.audioSource(with: constraints)
        let track = factory.audioTrack(with: source, trackId: "lynk-microphone")
        audioTrack = track
        let transceiver = RTCRtpTransceiverInit()
        transceiver.direction = .sendRecv
        _ = peer.addTransceiver(with: track, init: transceiver)

        let channelConfiguration = RTCDataChannelConfiguration()
        guard let channel = peer.dataChannel(forLabel: "oai-events", configuration: channelConfiguration) else {
            throw RealtimeVoiceTransportError.peerCreationFailed
        }
        channel.delegate = self
        dataChannel = channel

        let offer = try await withCheckedThrowingContinuation { continuation in
            peer.offer(for: constraints) { description, error in
                if let description { continuation.resume(returning: description) }
                else { continuation.resume(throwing: RealtimeVoiceTransportError.offerFailed(error?.localizedDescription ?? "Unknown error")) }
            }
        }
        try await setLocalDescription(offer, on: peer)

        let deadline = ContinuousClock.now.advanced(by: .milliseconds(2_500))
        while !iceGatheringComplete, ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(50))
        }
        guard let local = peer.localDescription?.sdp, !local.isEmpty else {
            throw RealtimeVoiceTransportError.noLocalDescription
        }
        return local
    }

    func applyAnswer(_ sdp: String) async throws {
        guard let peerConnection else { throw RealtimeVoiceTransportError.peerCreationFailed }
        let answer = RTCSessionDescription(type: .answer, sdp: sdp)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            peerConnection.setRemoteDescription(answer) { error in
                if let error { continuation.resume(throwing: RealtimeVoiceTransportError.answerFailed(error.localizedDescription)) }
                else { continuation.resume(returning: ()) }
            }
        }
    }

    func setMuted(_ muted: Bool) {
        audioTrack?.isEnabled = !muted
    }

    func send(event: [String: JSONValue]) -> Bool {
        guard let dataChannel, dataChannel.readyState == .open,
              let data = try? JSONEncoder().encode(event) else { return false }
        return dataChannel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }

    func close() {
        dataChannel?.delegate = nil
        dataChannel?.close()
        dataChannel = nil
        audioTrack?.isEnabled = false
        audioTrack = nil
        peerConnection?.close()
        peerConnection = nil
    }

    private func setLocalDescription(_ description: RTCSessionDescription, on peer: RTCPeerConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(description) { error in
                if let error { continuation.resume(throwing: RealtimeVoiceTransportError.offerFailed(error.localizedDescription)) }
                else { continuation.resume(returning: ()) }
            }
        }
    }
}

extension WebRTCRealtimeTransport: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        let label: String
        switch newState {
        case .connected: label = "connected"
        case .completed: label = "completed"
        case .failed: label = "failed"
        case .disconnected: label = "disconnected"
        case .closed: label = "closed"
        default: label = "connecting"
        }
        Task { @MainActor [weak self] in self?.onConnectionState?(label) }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard newState == .complete else { return }
        Task { @MainActor [weak self] in self?.iceGatheringComplete = true }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        Task { @MainActor [weak self] in
            self?.dataChannel?.delegate = nil
            self?.dataChannel = dataChannel
            dataChannel.delegate = self
        }
    }
}

extension WebRTCRealtimeTransport: RTCDataChannelDelegate {
    nonisolated func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {}

    nonisolated func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        guard !buffer.isBinary, let value = String(data: buffer.data, encoding: .utf8) else { return }
        Task { @MainActor [weak self] in self?.onEvent?(value) }
    }
}
