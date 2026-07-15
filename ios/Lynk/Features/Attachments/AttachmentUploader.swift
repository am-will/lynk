import Foundation

struct AttachmentUploader: Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func upload(
        _ attachment: StoredChatAttachment,
        endpoint: BridgeEndpoint,
        token: String,
        deviceID: String,
        sessionKey: String
    ) async throws -> ChatAttachmentReference {
        let size = try attachment.localURL.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init)
        guard size == attachment.sizeBytes else { throw AttachmentError.changed(attachment.displayName) }
        let request = try Self.makeRequest(
            attachment: attachment,
            endpoint: endpoint,
            token: token,
            deviceID: deviceID,
            sessionKey: sessionKey
        )
        let (data, response) = try await session.upload(for: request, fromFile: attachment.localURL)
        try Self.validate(data: data, response: response, attachment: attachment)
        return attachment.reference
    }

    static func makeRequest(
        attachment: StoredChatAttachment,
        endpoint: BridgeEndpoint,
        token: String,
        deviceID: String,
        sessionKey: String
    ) throws -> URLRequest {
        guard !sessionKey.isEmpty else { throw AttachmentError.sessionRequired }
        guard var components = URLComponents(
            url: endpoint.httpBaseURL.appending(path: "api/blobs/\(attachment.id)"),
            resolvingAgainstBaseURL: false
        ) else { throw AttachmentError.bridgeUnavailable }
        components.queryItems = [
            URLQueryItem(name: "displayName", value: attachment.displayName),
            URLQueryItem(name: "mimeType", value: attachment.mimeType),
            URLQueryItem(name: "kind", value: attachment.kind),
            URLQueryItem(name: "sha256", value: attachment.sha256)
        ]
        guard let url = components.url else { throw AttachmentError.bridgeUnavailable }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(deviceID, forHTTPHeaderField: "X-Lynk-Device-Id")
        request.setValue(sessionKey, forHTTPHeaderField: "X-Lynk-Session-Key")
        request.setValue(attachment.mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue(String(attachment.sizeBytes), forHTTPHeaderField: "Content-Length")
        return request
    }

    static func validate(data: Data, response: URLResponse, attachment: StoredChatAttachment) throws {
        guard data.count <= 16 * 1_024, let http = response as? HTTPURLResponse else {
            throw AttachmentError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            let detail = (object?["error"] as? String) ?? String(data: data, encoding: .utf8) ?? "Unknown error"
            throw AttachmentError.uploadFailed(http.statusCode, String(detail.prefix(240)))
        }
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let blob = object["blob"] as? [String: Any],
            blob["id"] as? String == attachment.id,
            blob["sha256"] as? String == attachment.sha256,
            (blob["sizeBytes"] as? NSNumber)?.int64Value == attachment.sizeBytes
        else { throw AttachmentError.invalidResponse }
    }
}
