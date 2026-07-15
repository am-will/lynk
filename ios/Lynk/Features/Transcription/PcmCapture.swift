import AVFoundation
import Foundation

final class PcmCapture: @unchecked Sendable {
    struct Result: Sendable {
        let fileURL: URL
        let sampleRate: Int
        let byteCount: Int64
        let heardSpeech: Bool
    }

    private let lock = NSLock()
    private let fileURL: URL
    private let file: FileHandle
    private let sampleRate: Int
    private let policy: TranscriptionCapturePolicy
    private let startedAt: Date
    private var lastProgressAt: Date
    private var lastSpeechAt: Date
    private var byteCount: Int64 = 0
    private var heardSpeech = false
    private var closed = false

    init(fileURL: URL, sampleRate: Int, policy: TranscriptionCapturePolicy = TranscriptionCapturePolicy()) throws {
        guard FileManager.default.createFile(atPath: fileURL.path, contents: nil) else {
            throw TranscriptionError.recordingFailed("Could not create a bounded capture file.")
        }
        self.fileURL = fileURL
        self.file = try FileHandle(forWritingTo: fileURL)
        self.sampleRate = sampleRate
        self.policy = policy
        let now = Date()
        startedAt = now
        lastProgressAt = now
        lastSpeechAt = now
    }

    func append(_ buffer: AVAudioPCMBuffer) throws -> (level: Float, stop: TranscriptionStopReason?) {
        guard let channel = buffer.floatChannelData?[0] else {
            throw TranscriptionError.recordingFailed("The microphone returned an unsupported audio format.")
        }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return (0, currentStopReason()) }
        var pcm = Data(capacity: count * 2)
        var squareSum: Double = 0
        for index in 0..<count {
            let sample = max(-1, min(1, channel[index]))
            squareSum += Double(sample * sample)
            var intSample = Int16(sample * Float(Int16.max)).littleEndian
            Swift.withUnsafeBytes(of: &intSample) { pcm.append(contentsOf: $0) }
        }
        let level = Float(min(sqrt(squareSum / Double(count)) * 3, 1))

        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return (0, nil) }
        if byteCount + Int64(pcm.count) > policy.maximumBytes { return (level, .maximumBytes) }
        try file.write(contentsOf: pcm)
        byteCount += Int64(pcm.count)
        let now = Date()
        lastProgressAt = now
        if level > policy.silenceFloor {
            heardSpeech = true
            lastSpeechAt = now
        }
        return (level, stopReason(at: now))
    }

    func currentStopReason() -> TranscriptionStopReason? {
        lock.lock()
        defer { lock.unlock() }
        return closed ? nil : stopReason(at: Date())
    }

    func finish() throws -> Result {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { throw TranscriptionError.invalidAudio }
        closed = true
        try file.close()
        return Result(fileURL: fileURL, sampleRate: sampleRate, byteCount: byteCount, heardSpeech: heardSpeech)
    }

    func cancel() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        try? file.close()
        lock.unlock()
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func stopReason(at now: Date) -> TranscriptionStopReason? {
        if byteCount >= policy.maximumBytes { return .maximumBytes }
        if now.timeIntervalSince(startedAt) >= policy.maximumDuration { return .maximumDuration }
        if !heardSpeech, now.timeIntervalSince(startedAt) >= policy.initialSilence { return .initialSilence }
        if now.timeIntervalSince(lastProgressAt) >= policy.noProgress { return .noProgress }
        if heardSpeech, now.timeIntervalSince(lastSpeechAt) >= policy.trailingSilence { return .trailingSilence }
        return nil
    }
}
