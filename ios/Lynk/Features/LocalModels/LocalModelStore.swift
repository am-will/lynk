import Foundation
import Observation

struct LocalModelFileStore: @unchecked Sendable {
    private let fileManager: FileManager
    private let rootOverride: URL?

    init(fileManager: FileManager = .default, root: URL? = nil) {
        self.fileManager = fileManager
        self.rootOverride = root
    }

    func loadManifest() throws -> [ImportedLocalModel] {
        let url = try rootDirectory().appending(path: "models.json")
        guard fileManager.fileExists(atPath: url.path) else { return [] }
        return try JSONDecoder().decode([ImportedLocalModel].self, from: Data(contentsOf: url))
            .filter { fileManager.fileExists(atPath: modelURL($0).path) }
    }

    func importFile(_ source: URL, existing: [ImportedLocalModel]) throws -> ImportedLocalModel {
        let secured = source.startAccessingSecurityScopedResource()
        defer { if secured { source.stopAccessingSecurityScopedResource() } }
        let ext = source.pathExtension.lowercased()
        guard let kind = ext == "gguf" ? LocalModelKind.gguf : ext == "litertlm" ? .litertLM : nil else {
            throw LocalModelError.unsupportedFile
        }
        let values = try source.resourceValues(forKeys: [.fileSizeKey, .volumeAvailableCapacityForImportantUsageKey])
        guard let fileSize = values.fileSize.map(Int64.init), fileSize > 0 else { throw LocalModelError.emptyFile }
        let root = try rootDirectory()
        let rootValues = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        guard (rootValues.volumeAvailableCapacityForImportantUsage ?? 0) > fileSize + 256 * 1_024 * 1_024 else {
            throw LocalModelError.insufficientSpace
        }

        let id = UUID().uuidString.lowercased()
        let fileName = "\(id).\(ext)"
        let destination = root.appending(path: fileName)
        guard fileManager.createFile(atPath: destination.path, contents: nil) else { throw LocalModelError.missingFile }
        do {
            let input = try FileHandle(forReadingFrom: source)
            let output = try FileHandle(forWritingTo: destination)
            defer { try? input.close(); try? output.close() }
            var copied: Int64 = 0
            while let data = try input.read(upToCount: 1_024 * 1_024), !data.isEmpty {
                try output.write(contentsOf: data)
                copied += Int64(data.count)
            }
            guard copied == fileSize else { throw LocalModelError.missingFile }
            let model = ImportedLocalModel(
                id: id,
                displayName: String(source.lastPathComponent.prefix(160)),
                kind: kind,
                sizeBytes: fileSize,
                fileName: fileName,
                importedAt: Date()
            )
            try saveManifest(existing + [model])
            return model
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    func delete(_ model: ImportedLocalModel, remaining: [ImportedLocalModel]) throws {
        try? fileManager.removeItem(at: modelURL(model))
        try saveManifest(remaining)
    }

    func modelURL(_ model: ImportedLocalModel) -> URL {
        (try? rootDirectory().appending(path: model.fileName)) ?? URL(fileURLWithPath: model.fileName)
    }

    private func saveManifest(_ models: [ImportedLocalModel]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(models).write(to: try rootDirectory().appending(path: "models.json"), options: .atomic)
    }

    private func rootDirectory() throws -> URL {
        let root: URL
        if let rootOverride { root = rootOverride }
        else {
            let support = try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            root = support.appending(path: "Lynk/Models", directoryHint: .isDirectory)
        }
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableRoot = root
        try? mutableRoot.setResourceValues(values)
        return root
    }
}

@MainActor
@Observable
final class LocalModelStore {
    var models: [ImportedLocalModel] = []
    var isImporting = false
    var error: String?

    private let files: LocalModelFileStore

    init(files: LocalModelFileStore = LocalModelFileStore()) {
        self.files = files
        models = (try? files.loadManifest()) ?? []
    }

    func importFile(_ url: URL) async {
        isImporting = true
        error = nil
        defer { isImporting = false }
        do {
            let files = self.files
            let existing = models
            let model = try await Task.detached { try files.importFile(url, existing: existing) }.value
            models.append(model)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func delete(_ model: ImportedLocalModel) {
        let remaining = models.filter { $0.id != model.id }
        do {
            try files.delete(model, remaining: remaining)
            models = remaining
        } catch { self.error = error.localizedDescription }
    }

    func url(for model: ImportedLocalModel) -> URL { files.modelURL(model) }
}
