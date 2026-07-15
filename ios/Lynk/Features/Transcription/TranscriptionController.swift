import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class TranscriptionController {
    var isRecording = false
    var isTranscribing = false
    var audioLevel: Float = 0
    var error: String?

    private let engine = AVAudioEngine()
    private let client: TranscriptionClient
    private var capture: PcmCapture?
    private var monitorTask: Task<Void, Never>?
    private var apiKey = ""
    private var onTranscript: ((String) -> Void)?
    private var automaticStopRequested = false
    private var hasInputTap = false

    init(client: TranscriptionClient = TranscriptionClient()) {
        self.client = client
    }

    func start(apiKey: String, onTranscript: @escaping (String) -> Void) async {
        guard !isRecording, !isTranscribing else { return }
        guard await requestPermission() else {
            error = TranscriptionError.permissionRequired.localizedDescription
            return
        }
        self.apiKey = apiKey
        self.onTranscript = onTranscript
        self.error = nil
        automaticStopRequested = false

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setActive(true)
            let input = engine.inputNode
            let format = input.inputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { throw TranscriptionError.invalidAudio }
            let fileURL = FileManager.default.temporaryDirectory.appending(path: "lynk-transcription-\(UUID().uuidString).pcm")
            let capture = try PcmCapture(fileURL: fileURL, sampleRate: Int(format.sampleRate.rounded()))
            self.capture = capture
            input.installTap(onBus: 0, bufferSize: 2_048, format: format) { [weak self, capture] buffer, _ in
                do {
                    let update = try capture.append(buffer)
                    Task { @MainActor [weak self] in
                        self?.audioLevel = update.level
                        if update.stop != nil { self?.requestAutomaticStop() }
                    }
                } catch {
                    Task { @MainActor [weak self] in
                        self?.error = error.localizedDescription
                        self?.cancel()
                    }
                }
            }
            hasInputTap = true
            engine.prepare()
            try engine.start()
            isRecording = true
            monitorTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(250))
                    guard let self, self.isRecording else { return }
                    if self.capture?.currentStopReason() != nil { self.requestAutomaticStop(); return }
                }
            }
        } catch {
            stopEngine()
            capture?.cancel()
            capture = nil
            self.error = error.localizedDescription
        }
    }

    func stopAndTranscribe() async {
        guard isRecording, let capture else { return }
        isRecording = false
        isTranscribing = true
        audioLevel = 0
        monitorTask?.cancel()
        stopEngine()
        self.capture = nil

        do {
            let result = try capture.finish()
            defer { try? FileManager.default.removeItem(at: result.fileURL) }
            let duration = Double(result.byteCount) / Double(result.sampleRate * 2)
            guard result.heardSpeech, duration >= TranscriptionCapturePolicy().minimumDuration else {
                throw TranscriptionError.invalidAudio
            }
            let pcm = try Data(contentsOf: result.fileURL, options: .mappedIfSafe)
            let wav = try WavEncoder.encode(pcm16: pcm, sampleRate: result.sampleRate)
            if let transcript = try await client.transcribe(wav: wav, apiKey: apiKey) {
                onTranscript?(transcript)
            }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        isTranscribing = false
        onTranscript = nil
        deactivateAudioSession()
    }

    func cancel() {
        monitorTask?.cancel()
        monitorTask = nil
        stopEngine()
        capture?.cancel()
        capture = nil
        isRecording = false
        isTranscribing = false
        audioLevel = 0
        onTranscript = nil
        deactivateAudioSession()
    }

    private func requestAutomaticStop() {
        guard !automaticStopRequested, isRecording else { return }
        automaticStopRequested = true
        Task { [weak self] in await self?.stopAndTranscribe() }
    }

    private func requestPermission() async -> Bool {
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

    private func stopEngine() {
        if hasInputTap {
            engine.inputNode.removeTap(onBus: 0)
            hasInputTap = false
        }
        engine.stop()
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
