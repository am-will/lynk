import CryptoKit
import Foundation
import UniformTypeIdentifiers

struct AttachmentImporter: @unchecked Sendable {
    private let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    func importFile(at source: URL, existing: [StoredChatAttachment]) throws -> StoredChatAttachment {
        let secured = source.startAccessingSecurityScopedResource()
        defer { if secured { source.stopAccessingSecurityScopedResource() } }

        let values = try source.resourceValues(forKeys: [.fileSizeKey, .nameKey, .contentTypeKey, .isRegularFileKey])
        let displayName = normalizedDisplayName(values.name ?? source.lastPathComponent)
        guard values.isRegularFile != false, let declaredSize = values.fileSize.map(Int64.init) else {
            throw AttachmentError.unreadable(displayName)
        }
        try AttachmentPolicy.validate(existing: existing, adding: declaredSize, name: displayName)

        let directory = try attachmentDirectory()
        let id = "blob_\(UUID().uuidString.lowercased())"
        let destination = directory.appending(path: id, directoryHint: .notDirectory)
        guard fileManager.createFile(atPath: destination.path, contents: nil) else {
            throw AttachmentError.unreadable(displayName)
        }

        do {
            let input = try FileHandle(forReadingFrom: source)
            let output = try FileHandle(forWritingTo: destination)
            defer { try? input.close(); try? output.close() }
            var hasher = SHA256()
            var copied: Int64 = 0
            while let chunk = try input.read(upToCount: 64 * 1_024), !chunk.isEmpty {
                copied += Int64(chunk.count)
                try AttachmentPolicy.validate(existing: existing, adding: copied, name: displayName)
                hasher.update(data: chunk)
                try output.write(contentsOf: chunk)
            }
            guard copied == declaredSize else { throw AttachmentError.changed(displayName) }
            let mimeType = normalizedMIMEType(values.contentType?.preferredMIMEType
                ?? UTType(filenameExtension: source.pathExtension)?.preferredMIMEType
                ?? "application/octet-stream")
            return StoredChatAttachment(
                id: id,
                kind: mimeType.hasPrefix("image/") ? "image" : "file",
                displayName: displayName,
                mimeType: mimeType,
                sizeBytes: copied,
                localURL: destination,
                sha256: hasher.finalize().map { String(format: "%02x", $0) }.joined()
            )
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    private func normalizedDisplayName(_ rawValue: String) -> String {
        let filtered = rawValue.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        let trimmed = String(String.UnicodeScalarView(filtered)).trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? "Attachment" : trimmed).prefix(120))
    }

    private func normalizedMIMEType(_ rawValue: String) -> String {
        let value = rawValue.lowercased()
        let pattern = #"^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$"#
        return value.range(of: pattern, options: .regularExpression) == nil ? "application/octet-stream" : value
    }

    private func attachmentDirectory() throws -> URL {
        let support = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appending(path: "Lynk/Attachments", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try? mutableDirectory.setResourceValues(values)
        return directory
    }
}
