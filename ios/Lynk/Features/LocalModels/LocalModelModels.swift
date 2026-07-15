import Foundation

enum LocalModelKind: String, Codable, CaseIterable, Sendable {
    case gguf
    case litertLM = "litertlm"

    var label: String { self == .gguf ? "GGUF (llama.cpp)" : "LiteRT-LM" }
}

struct ImportedLocalModel: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let displayName: String
    let kind: LocalModelKind
    let sizeBytes: Int64
    let fileName: String
    let importedAt: Date
}

enum LocalModelError: LocalizedError, Equatable {
    case unsupportedFile
    case emptyFile
    case insufficientSpace
    case missingFile
    case liteRTUnavailable
    case ggufRuntimeUnavailable
    case noModelSelected
    case generationFailed(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedFile: "Choose a .gguf or .litertlm model file."
        case .emptyFile: "The selected model file is empty."
        case .insufficientSpace: "Not enough free space to import this model safely."
        case .missingFile: "The imported model file is no longer available."
        case .liteRTUnavailable: "LiteRT-LM files can be imported, but no compatible iOS LiteRT-LM runtime is currently available."
        case .ggufRuntimeUnavailable: "GGUF inference requires Lynk's pinned llama.cpp iOS runtime."
        case .noModelSelected: "Import and select a local model first."
        case let .generationFailed(message): "Local generation failed: \(message)"
        }
    }
}

enum LocalRuntimeAvailability {
    static func message(for kind: LocalModelKind) -> String? {
        switch kind {
        case .litertLM: LocalModelError.liteRTUnavailable.localizedDescription
        case .gguf: LlamaRuntime.isAvailable ? nil : LocalModelError.ggufRuntimeUnavailable.localizedDescription
        }
    }
}
