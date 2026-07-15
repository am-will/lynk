import Foundation

struct TranscriptionCapturePolicy: Equatable, Sendable {
    let maximumBytes: Int64 = 25 * 1_024 * 1_024
    let maximumDuration: TimeInterval = 120
    let initialSilence: TimeInterval = 10
    let noProgress: TimeInterval = 3
    let trailingSilence: TimeInterval = 1.5
    let silenceFloor: Float = 0.02
    let minimumDuration: TimeInterval = 0.5
}

enum TranscriptionStopReason: Equatable, Sendable {
    case maximumBytes
    case maximumDuration
    case initialSilence
    case noProgress
    case trailingSilence
}

enum TranscriptSanitizer {
    static func clean(_ rawValue: String?) -> String? {
        guard var value = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        value = value.replacingOccurrences(of: #"\([^)]{0,40}\)"#, with: "", options: .regularExpression)
        value = value.replacingOccurrences(of: #"\[[^\]]{0,40}\]"#, with: "", options: .regularExpression)
        value = value.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let silencePhrases: Set<String> = [
            "", ".", "...", "…", "you", "you.", "you you", "you you you", "thanks", "thank you",
            "thank you.", "silence", "silent", "blank audio", "blank_audio", "no audio", "music",
            "soft music", "quiet music", "background music"
        ]
        guard !silencePhrases.contains(value.lowercased()), value.contains(where: { $0.isLetter || $0.isNumber }) else {
            return nil
        }
        return value
    }
}

enum WavEncoder {
    static func encode(pcm16 data: Data, sampleRate: Int) throws -> Data {
        guard sampleRate > 0, data.count <= Int(UInt32.max) - 36 else {
            throw TranscriptionError.invalidAudio
        }
        var result = Data(capacity: 44 + data.count)
        result.append(contentsOf: "RIFF".utf8)
        result.appendLittleEndian(UInt32(36 + data.count))
        result.append(contentsOf: "WAVEfmt ".utf8)
        result.appendLittleEndian(UInt32(16))
        result.appendLittleEndian(UInt16(1))
        result.appendLittleEndian(UInt16(1))
        result.appendLittleEndian(UInt32(sampleRate))
        result.appendLittleEndian(UInt32(sampleRate * 2))
        result.appendLittleEndian(UInt16(2))
        result.appendLittleEndian(UInt16(16))
        result.append(contentsOf: "data".utf8)
        result.appendLittleEndian(UInt32(data.count))
        result.append(data)
        return result
    }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
}
