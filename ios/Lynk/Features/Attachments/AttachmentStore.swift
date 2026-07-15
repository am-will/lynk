import Foundation
import Observation

@MainActor
@Observable
final class AttachmentStore {
    var pending: [StoredChatAttachment] = []
    var isImporting = false
    var isUploading = false
    var error: String?

    private let importer: AttachmentImporter
    private let uploader: AttachmentUploader

    init(importer: AttachmentImporter = AttachmentImporter(), uploader: AttachmentUploader = AttachmentUploader()) {
        self.importer = importer
        self.uploader = uploader
    }

    func importFiles(_ urls: [URL]) async {
        isImporting = true
        error = nil
        defer { isImporting = false }
        for url in urls {
            do {
                let existing = pending
                let importer = self.importer
                let attachment = try await Task.detached { try importer.importFile(at: url, existing: existing) }.value
                pending.append(attachment)
            } catch {
                self.error = error.localizedDescription
                break
            }
        }
    }

    func remove(_ attachment: StoredChatAttachment) {
        pending.removeAll { $0.id == attachment.id }
        try? FileManager.default.removeItem(at: attachment.localURL)
    }

    func uploadAll(endpoint: BridgeEndpoint?, snapshot: SettingsSnapshot, sessionKey: String?) async throws -> [ChatAttachmentReference] {
        guard !pending.isEmpty else { return [] }
        guard let endpoint else { throw AttachmentError.bridgeUnavailable }
        guard let sessionKey, !sessionKey.isEmpty else { throw AttachmentError.sessionRequired }
        isUploading = true
        error = nil
        defer { isUploading = false }
        var references: [ChatAttachmentReference] = []
        for attachment in pending {
            references.append(try await uploader.upload(
                attachment,
                endpoint: endpoint,
                token: snapshot.token,
                deviceID: snapshot.deviceID,
                sessionKey: sessionKey
            ))
        }
        return references
    }

    func removeUploaded(_ references: [ChatAttachmentReference]) {
        let uploaded = Set(references.map(\.id))
        for attachment in pending where uploaded.contains(attachment.id) {
            try? FileManager.default.removeItem(at: attachment.localURL)
        }
        pending.removeAll { uploaded.contains($0.id) }
    }
}
