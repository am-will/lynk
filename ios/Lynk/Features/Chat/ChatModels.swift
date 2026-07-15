import Foundation

struct ChatAttachmentMetadata: Identifiable, Equatable, Sendable {
    let id: String
    let kind: String
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
}

struct ChatReasoningOption: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
}

struct ChatModelOption: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
    let provider: String?
    let harnessID: String?
    let harnessLabel: String?
    let modelID: String?
    let contextWindow: Int64?
    let available: Bool?
    let reasoningOptions: [ChatReasoningOption]?
    let defaultReasoningEffort: String?

    var groupLabel: String { harnessLabel ?? harnessID ?? provider ?? "Other" }
}

struct ChatSession: Identifiable, Equatable, Sendable {
    var id: String { key }
    let key: String
    let sessionID: String?
    let label: String?
    let displayName: String?
    let harnessID: String?
    let harnessLabel: String?
    let workspacePath: String?
    let workspaceName: String?
    let threadPath: String?
    let preview: String?
    let source: String?
    let updatedAt: Int64?
    let model: String?
    let contextTokens: Int64?
    let totalTokens: Int64?
    let estimatedCostUSD: Double?
    let hasActiveRun: Bool?

    var title: String { displayName ?? label ?? workspaceName ?? "Chat" }
    var subtitle: String? { preview ?? workspacePath ?? harnessLabel }
}

struct ChatUsage: Equatable, Sendable {
    var inputTokens: Int64?
    var outputTokens: Int64?
    var totalTokens: Int64?
    var contextTokens: Int64?
    var estimatedCostUSD: Double?

    var contextRatio: Double? {
        guard let totalTokens, let contextTokens, contextTokens > 0 else { return nil }
        return min(max(Double(totalTokens) / Double(contextTokens), 0), 1)
    }
}

struct ChatToolAction: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
    let command: String
    let args: [String: JSONValue]
    let style: String?
}

struct ChatToolEvent: Equatable, Sendable {
    let eventID: String
    var runID: String?
    var toolName: String
    var title: String
    var status: String
    var summary: String?
    var args: JSONValue?
    var output: JSONValue?
    var error: String?
    var actions: [ChatToolAction]
    var isExpanded = false
}

struct ChatTimelineItem: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable { case message, reasoning, tool }

    let id: String
    var kind: Kind
    var role: String?
    var text: String
    var attachments: [ChatAttachmentMetadata]
    var timestamp: Int64?
    var runID: String?
    var isStreaming = false
    var isClearing = false
    var tool: ChatToolEvent?
}

struct ChatUnreadReply: Equatable, Sendable {
    var runIDs: Set<String>
    var count: Int
    var preview: String?
}

struct ChatCommand: Identifiable, Equatable, Sendable {
    var id: String { name }
    let name: String
    let description: String?
    let aliases: [String]
}

struct ChatToolSummary: Identifiable, Equatable, Sendable {
    let id: String
    let label: String?
    let description: String?
}

enum ActiveSendMode: String, CaseIterable, Identifiable {
    case steer
    case queue
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}
