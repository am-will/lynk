import Foundation

enum TranscriptionError: LocalizedError, Equatable {
    case permissionRequired
    case keyRequired
    case invalidAudio
    case recordingFailed(String)
    case requestFailed(Int, String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .permissionRequired: "Microphone permission is required for transcription."
        case .keyRequired: "An OpenAI API key is required for transcription."
        case .invalidAudio: "The recording did not contain enough speech to transcribe."
        case let .recordingFailed(detail): "Could not record audio: \(detail)"
        case let .requestFailed(status, detail): "Transcription failed (\(status)): \(detail)"
        case .invalidResponse: "OpenAI returned an invalid transcription response."
        }
    }
}

struct TranscriptionClient: Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func transcribe(wav: Data, apiKey: String) async throws -> String? {
        guard !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TranscriptionError.keyRequired
        }
        let boundary = "Lynk-\(UUID().uuidString)"
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/audio/transcriptions")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 75
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.multipartBody(wav: wav, boundary: boundary)

        let (data, response) = try await session.data(for: request)
        guard data.count <= 64 * 1_024, let http = response as? HTTPURLResponse else {
            throw TranscriptionError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            let detail = String(data: data, encoding: .utf8).map { String($0.prefix(400)) } ?? "Unknown error"
            throw TranscriptionError.requestFailed(http.statusCode, detail)
        }
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let text = object["text"] as? String {
            return TranscriptSanitizer.clean(text)
        }
        return TranscriptSanitizer.clean(String(data: data, encoding: .utf8))
    }

    static func multipartBody(wav: Data, boundary: String) -> Data {
        var data = Data()
        data.append(contentsOf: "--\(boundary)\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n".utf8)
        data.append(contentsOf: "--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8)
        data.append(wav)
        data.append(contentsOf: "\r\n--\(boundary)--\r\n".utf8)
        return data
    }
}
