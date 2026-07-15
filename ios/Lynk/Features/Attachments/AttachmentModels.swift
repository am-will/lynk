import Foundation

enum AttachmentPolicy {
    static let maximumCount = 8
    static let maximumItemBytes: Int64 = 50 * 1_024 * 1_024
    static let maximumMessageBytes: Int64 = 100 * 1_024 * 1_024

    static func validate(existing: [StoredChatAttachment], adding size: Int64, name: String) throws {
        guard size > 0, size <= maximumItemBytes else {
            throw AttachmentError.tooLarge(name)
        }
        guard existing.count < maximumCount else {
            throw AttachmentError.tooMany
        }
        let total = existing.reduce(Int64.zero) { $0 + $1.sizeBytes }
        guard total <= maximumMessageBytes - size else {
            throw AttachmentError.messageTooLarge
        }
    }
}

enum AttachmentError: LocalizedError, Equatable {
    case tooMany
    case tooLarge(String)
    case messageTooLarge
    case unreadable(String)
    case changed(String)
    case sessionRequired
    case bridgeUnavailable
    case invalidResponse
    case uploadFailed(Int, String)

    var errorDescription: String? {
        switch self {
        case .tooMany: "Attach at most 8 files per message."
        case let .tooLarge(name): "\(name) must be between 1 byte and 50 MB."
        case .messageTooLarge: "Attachments exceed the 100 MB message limit."
        case let .unreadable(name): "\(name) could not be read."
        case let .changed(name): "\(name) changed after it was attached. Reattach the file."
        case .sessionRequired: "Select a chat session before sending an attachment."
        case .bridgeUnavailable: "Connect to the bridge before uploading attachments."
        case .invalidResponse: "The bridge returned an invalid attachment response."
        case let .uploadFailed(status, detail): "Attachment upload failed (\(status)): \(detail)"
        }
    }
}

struct StoredChatAttachment: Identifiable, Equatable, Sendable {
    let id: String
    let kind: String
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
    let localURL: URL
    let sha256: String

    var metadata: ChatAttachmentMetadata {
        ChatAttachmentMetadata(
            id: id,
            kind: kind,
            displayName: displayName,
            mimeType: mimeType,
            sizeBytes: sizeBytes
        )
    }

    var reference: ChatAttachmentReference {
        ChatAttachmentReference(
            id: id,
            kind: kind,
            displayName: displayName,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            sha256: sha256
        )
    }
}

struct ChatAttachmentReference: Equatable, Sendable {
    let id: String
    let kind: String
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String

    var json: JSONValue {
        .object([
            "id": .string(id),
            "kind": .string(kind),
            "displayName": .string(displayName),
            "mimeType": .string(mimeType),
            "sizeBytes": .number(Double(sizeBytes)),
            "sha256": .string(sha256)
        ])
    }
}
