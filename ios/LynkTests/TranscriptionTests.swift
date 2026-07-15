import Foundation
import XCTest
@testable import Lynk

final class TranscriptionTests: XCTestCase {
    func testWavEncoderWritesMonoPCM16Header() throws {
        let pcm = Data([0x01, 0x02, 0x03, 0x04])
        let wav = try WavEncoder.encode(pcm16: pcm, sampleRate: 24_000)

        XCTAssertEqual(String(data: wav[0..<4], encoding: .ascii), "RIFF")
        XCTAssertEqual(String(data: wav[8..<12], encoding: .ascii), "WAVE")
        XCTAssertEqual(String(data: wav[36..<40], encoding: .ascii), "data")
        XCTAssertEqual(wav.count, 48)
        XCTAssertEqual(Array(wav.suffix(4)), Array(pcm))
    }

    func testTranscriptSanitizerRejectsSilenceAndKeepsSpeech() {
        XCTAssertNil(TranscriptSanitizer.clean("[soft music]"))
        XCTAssertNil(TranscriptSanitizer.clean("Thank you."))
        XCTAssertEqual(TranscriptSanitizer.clean("  [noise] Ship   the feature.  "), "Ship the feature.")
    }

    func testMultipartRequestUsesBoundedModelAndWavPart() {
        let body = TranscriptionClient.multipartBody(wav: Data([1, 2, 3]), boundary: "boundary")
        let text = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(text.contains("gpt-4o-mini-transcribe"))
        XCTAssertTrue(text.contains("filename=\"audio.wav\""))
        XCTAssertTrue(text.contains("Content-Type: audio/wav"))
        XCTAssertTrue(text.hasSuffix("--boundary--\r\n"))
    }

    func testCapturePolicyMatchesAndroidResourceBounds() {
        let policy = TranscriptionCapturePolicy()
        XCTAssertEqual(policy.maximumBytes, 25 * 1_024 * 1_024)
        XCTAssertEqual(policy.maximumDuration, 120)
        XCTAssertEqual(policy.initialSilence, 10)
        XCTAssertEqual(policy.trailingSilence, 1.5)
        XCTAssertEqual(policy.minimumDuration, 0.5)
    }
}
