import Foundation
import Observation

@MainActor
@Observable
final class ChatStore {
    var sessionKey: String?
    var sessionID: String?
    var harnessID: String?
    var harnessLabel: String?
    var activeRunID: String?
    var isRunning = false
    var status: String?
    var error: String?
    var selectedModel: String?
    var reasoningEffort = "medium"
    var timeline: [ChatTimelineItem] = []
    var sessions: [ChatSession] = []
    var models: [ChatModelOption] = []
    var reasoningOptions: [ChatReasoningOption] = ChatStore.defaultReasoningOptions
    var commands: [ChatCommand] = []
    var tools: [ChatToolSummary] = []
    var usage = ChatUsage()
    var unreadReplies: [String: ChatUnreadReply] = [:]

    static let defaultReasoningOptions = ["low", "medium", "high", "xhigh"].map {
        ChatReasoningOption(id: $0, label: $0)
    }

    var selectedModelOption: ChatModelOption? { models.first { $0.id == selectedModel } }
    var availableModels: [ChatModelOption] { models.filter { $0.available != false } }
    var effectiveReasoningOptions: [ChatReasoningOption] {
        guard let modelOptions = selectedModelOption?.reasoningOptions, !modelOptions.isEmpty else {
            return reasoningOptions
        }
        return modelOptions
    }

    func receive(_ message: [String: JSONValue]) {
        switch message.string("type") {
        case "agent_status": status = message.string("text")
        case "chat.state": reduceState(message)
        case "chat.history": reduceHistory(message)
        case "chat.message": reduceMessage(message)
        case "chat.delta": reduceDelta(message, reasoning: false)
        case "chat.reasoning_delta": reduceDelta(message, reasoning: true)
        case "chat.reasoning_clear": clearReasoning(runID: message.string("runId"))
        case "chat.final": reduceFinal(message)
        case "chat.error": reduceError(message)
        case "chat.tool_event": reduceTool(message)
        case "chat.models": reduceModels(message)
        case "chat.sessions": reduceSessions(message)
        case "chat.commands": reduceCommands(message)
        case "chat.tools": reduceTools(message)
        case "chat.usage": reduceUsage(message)
        case "chat.reply_available": reduceReplyAvailable(message)
        default: break
        }
    }

    func send(text: String, delivery: ActiveSendMode, systemPrompt: String, deviceID: String, bridge: BridgeClient) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let localID = "local_\(UUID().uuidString)"
        timeline.append(ChatTimelineItem(
            id: localID,
            kind: .message,
            role: "user",
            text: trimmed,
            attachments: [],
            timestamp: Int64(Date().timeIntervalSince1970 * 1_000)
        ))
        error = nil
        status = "Sent"
        var payload: [String: JSONValue] = [
            "type": .string("chat.send"),
            "deviceId": .string(deviceID),
            "text": .string(trimmed),
            "delivery": .string(isRunning ? delivery.rawValue : "normal"),
            "systemPrompt": .string(String(systemPrompt.prefix(32_000)))
        ]
        if let sessionKey { payload["sessionKey"] = .string(sessionKey) }
        if let sessionID { payload["sessionId"] = .string(sessionID) }
        if let selectedModel { payload["model"] = .string(selectedModel) }
        payload["reasoningEffort"] = .string(reasoningEffort)
        Task {
            if !(await bridge.send(payload)) {
                self.error = "Message was not sent. Reconnect to the bridge and try again."
            }
        }
    }

    func stop(deviceID: String, bridge: BridgeClient) {
        var payload: [String: JSONValue] = [
            "type": .string("chat.stop"),
            "deviceId": .string(deviceID),
            "reason": .string("Stopped from Lynk iOS")
        ]
        if let sessionKey { payload["sessionKey"] = .string(sessionKey) }
        if let activeRunID { payload["runId"] = .string(activeRunID) }
        Task { _ = await bridge.send(payload) }
    }

    func selectSession(_ key: String, deviceID: String, bridge: BridgeClient) {
        guard key != sessionKey else { return }
        sessionKey = key
        timeline = []
        usage = ChatUsage()
        unreadReplies[key] = nil
        Task {
            _ = await bridge.send([
                "type": .string("chat.select_session"),
                "deviceId": .string(deviceID),
                "sessionKey": .string(key)
            ])
        }
    }

    func createSession(label: String?, workspacePath: String?, createWorkspace: Bool, deviceID: String, bridge: BridgeClient) {
        var payload: [String: JSONValue] = [
            "type": .string("chat.new_session"),
            "deviceId": .string(deviceID)
        ]
        if let label = label?.trimmedNonempty { payload["label"] = .string(label) }
        if let selectedModel { payload["model"] = .string(selectedModel) }
        if let workspacePath = workspacePath?.trimmedNonempty {
            payload["workspacePath"] = .string(workspacePath)
            if createWorkspace { payload["createWorkspaceIfMissing"] = .bool(true) }
        }
        Task { _ = await bridge.send(payload) }
    }

    func setModel(_ model: String, deviceID: String, bridge: BridgeClient) {
        selectedModel = model
        var payload: [String: JSONValue] = [
            "type": .string("chat.set_model"),
            "deviceId": .string(deviceID),
            "model": .string(model)
        ]
        if let sessionKey { payload["sessionKey"] = .string(sessionKey) }
        Task { _ = await bridge.send(payload) }
    }

    func setReasoning(_ effort: String, deviceID: String, bridge: BridgeClient) {
        reasoningEffort = effort
        var payload: [String: JSONValue] = [
            "type": .string("chat.set_reasoning"),
            "deviceId": .string(deviceID),
            "reasoningEffort": .string(effort)
        ]
        if let sessionKey { payload["sessionKey"] = .string(sessionKey) }
        Task { _ = await bridge.send(payload) }
    }

    func perform(_ action: ChatToolAction, deviceID: String, bridge: BridgeClient) {
        Task {
            _ = await bridge.send([
                "type": .string("chat.control_command"),
                "deviceId": .string(deviceID),
                "command": .string(action.command),
                "args": .object(action.args)
            ])
        }
    }

    func toggleTool(_ eventID: String) {
        guard let index = timeline.firstIndex(where: { $0.tool?.eventID == eventID }) else { return }
        timeline[index].tool?.isExpanded.toggle()
    }

    private func reduceState(_ message: [String: JSONValue]) {
        guard let key = message.string("sessionKey") else { return }
        if sessionKey != key {
            sessionKey = key
            timeline = []
            usage = ChatUsage()
        }
        sessionID = message.optionalString("sessionId", preserving: sessionID)
        harnessID = message.optionalString("harnessId", preserving: harnessID)
        harnessLabel = message.optionalString("harnessLabel", preserving: harnessLabel)
        activeRunID = message.optionalString("runId", preserving: activeRunID)
        isRunning = message.bool("isRunning") ?? false
        if message.keys.contains("status") { status = message.string("status") }
        if message.keys.contains("model") { selectedModel = message.string("model") }
        if let effort = message.string("reasoningEffort") { reasoningEffort = effort }
        if !isRunning { activeRunID = nil }
        unreadReplies[key] = nil
    }

    private func reduceHistory(_ message: [String: JSONValue]) {
        guard let key = message.string("sessionKey"), sessionKey == nil || sessionKey == key else { return }
        sessionKey = key
        sessionID = message.string("sessionId") ?? sessionID
        let serverItems = message.array("messages").enumerated().compactMap { index, value in
            value.objectValue.flatMap { Self.historyItem($0, fallbackID: "history_\(index)") }
        }
        let optimistic = timeline.filter { item in
            guard item.id.hasPrefix("local_") || item.role == "system" else { return false }
            return !serverItems.contains { $0.role == item.role && $0.text == item.text && $0.attachments == item.attachments }
        }
        let blockedTools = timeline.filter { $0.tool?.status == "blocked" }
        timeline = (serverItems + optimistic + blockedTools).sorted { ($0.timestamp ?? 0) < ($1.timestamp ?? 0) }
    }

    private func reduceMessage(_ message: [String: JSONValue]) {
        guard message.string("sessionKey") == sessionKey,
              let object = message["message"]?.objectValue,
              let item = Self.historyItem(object, fallbackID: "message_\(UUID().uuidString)") else { return }
        if let index = timeline.firstIndex(where: { $0.id == item.id }) { timeline[index] = item }
        else if item.role == "user", let local = timeline.firstIndex(where: { $0.id.hasPrefix("local_") && $0.text == item.text }) {
            timeline[local] = item
        } else { timeline.append(item) }
    }

    private func reduceDelta(_ message: [String: JSONValue], reasoning: Bool) {
        guard message.string("sessionKey") == sessionKey,
              let runID = message.string("runId"),
              let delta = message.string("delta") else { return }
        if !reasoning { clearReasoning(runID: runID) }
        let id = reasoning ? "reasoning_\(runID)" : "assistant_\(runID)"
        let kind: ChatTimelineItem.Kind = reasoning ? .reasoning : .message
        if let index = timeline.firstIndex(where: { $0.id == id }) {
            timeline[index].text = message.bool("replace") == true ? delta : timeline[index].text + delta
            timeline[index].isStreaming = true
        } else {
            timeline.append(ChatTimelineItem(
                id: id,
                kind: kind,
                role: reasoning ? "reasoning" : "assistant",
                text: delta,
                attachments: [],
                runID: runID,
                isStreaming: true
            ))
        }
        activeRunID = runID
        isRunning = true
        error = nil
    }

    private func reduceFinal(_ message: [String: JSONValue]) {
        guard message.string("sessionKey") == sessionKey,
              let runID = message.string("runId") else { return }
        let text = message.string("text") ?? ""
        let id = "assistant_\(runID)"
        clearReasoning(runID: runID)
        if let index = timeline.firstIndex(where: { $0.id == id }) {
            timeline[index].text = text
            timeline[index].isStreaming = false
        } else {
            timeline.append(ChatTimelineItem(id: id, kind: .message, role: "assistant", text: text, attachments: [], runID: runID))
        }
        if activeRunID == runID { activeRunID = nil; isRunning = false }
        status = "Finished"
        error = nil
    }

    private func reduceError(_ message: [String: JSONValue]) {
        if let key = message.string("sessionKey"), let sessionKey, key != sessionKey { return }
        guard let text = message.string("message") else { return }
        let runID = message.string("runId")
        timeline.append(ChatTimelineItem(
            id: "system_\(runID ?? UUID().uuidString)",
            kind: .message,
            role: "system",
            text: text,
            attachments: [],
            timestamp: Int64(Date().timeIntervalSince1970 * 1_000),
            runID: runID
        ))
        error = text
        status = "Error"
        if runID == nil || runID == activeRunID { activeRunID = nil; isRunning = false }
        clearReasoning(runID: runID)
    }

    private func reduceTool(_ message: [String: JSONValue]) {
        guard message.string("sessionKey") == sessionKey,
              let eventID = message.string("eventId"),
              let toolName = message.string("toolName"),
              let title = message.string("title"),
              let toolStatus = message.string("status") else { return }
        let actions = message.array("actions").compactMap { value -> ChatToolAction? in
            guard let object = value.objectValue,
                  let id = object.string("id"), let label = object.string("label"), let command = object.string("command") else { return nil }
            return ChatToolAction(id: id, label: label, command: command, args: object["args"]?.objectValue ?? [:], style: object.string("style"))
        }
        if let index = timeline.firstIndex(where: { $0.tool?.eventID == eventID }), var prior = timeline[index].tool {
            prior.runID = message.string("runId") ?? prior.runID
            prior.toolName = toolName
            prior.title = title
            prior.status = toolStatus
            if message.keys.contains("summary") { prior.summary = message.string("summary") }
            if message.keys.contains("args") { prior.args = message["args"] }
            if message.keys.contains("output") { prior.output = message["output"] }
            if message.keys.contains("error") { prior.error = message.string("error") }
            if message.keys.contains("actions") { prior.actions = actions }
            timeline[index].tool = prior
        } else {
            let tool = ChatToolEvent(
                eventID: eventID,
                runID: message.string("runId"),
                toolName: toolName,
                title: title,
                status: toolStatus,
                summary: message.string("summary"),
                args: message["args"],
                output: message["output"],
                error: message.string("error"),
                actions: actions
            )
            timeline.append(ChatTimelineItem(id: "tool_\(eventID)", kind: .tool, role: nil, text: "", attachments: [], runID: tool.runID, tool: tool))
        }
        if let runID = message.string("runId"), toolStatus == "running" || toolStatus == "blocked" {
            activeRunID = runID
            isRunning = true
            if toolStatus == "blocked" { status = "Waiting for permission" }
        }
    }

    private func reduceModels(_ message: [String: JSONValue]) {
        models = message.array("models").compactMap { value in value.objectValue.flatMap(Self.model) }
        let defaults = message.array("reasoningOptions").compactMap { value in value.objectValue.flatMap(Self.reasoning) }
        if !defaults.isEmpty { reasoningOptions = defaults }
    }

    private func reduceSessions(_ message: [String: JSONValue]) {
        sessions = message.array("sessions").compactMap { value in value.objectValue.flatMap(Self.session) }
        if let selected = message.string("selectedSessionKey"), selected != sessionKey {
            sessionKey = selected
            timeline = []
            usage = ChatUsage()
        }
    }

    private func reduceCommands(_ message: [String: JSONValue]) {
        commands = message.array("commands").compactMap { value in
            guard let object = value.objectValue, let name = object.string("name") else { return nil }
            return ChatCommand(name: name, description: object.string("description"), aliases: object.array("textAliases").compactMap(\.stringValue))
        }
    }

    private func reduceTools(_ message: [String: JSONValue]) {
        guard message.string("sessionKey") == sessionKey else { return }
        tools = message.array("tools").compactMap { value in
            guard let object = value.objectValue, let id = object.string("id") else { return nil }
            return ChatToolSummary(id: id, label: object.string("label"), description: object.string("description"))
        }
    }

    private func reduceUsage(_ message: [String: JSONValue]) {
        guard message.string("sessionKey") == sessionKey, let object = message["usage"]?.objectValue else { return }
        usage = Self.usage(object)
    }

    private func reduceReplyAvailable(_ message: [String: JSONValue]) {
        guard let key = message.string("sessionKey"), let runID = message.string("runId"), key != sessionKey else { return }
        var unread = unreadReplies[key] ?? ChatUnreadReply(runIDs: [], count: 0, preview: nil)
        guard unread.runIDs.insert(runID).inserted else { return }
        unread.count += 1
        unread.preview = message.string("textPreview") ?? unread.preview
        unreadReplies[key] = unread
    }

    private func clearReasoning(runID: String?) {
        timeline.removeAll { item in
            guard item.kind == .reasoning else { return false }
            return runID == nil || item.runID == runID
        }
    }
}

private extension ChatStore {
    static func historyItem(_ object: [String: JSONValue], fallbackID: String) -> ChatTimelineItem? {
        guard let role = object.string("role"), let text = object.string("text") else { return nil }
        return ChatTimelineItem(
            id: object.string("id") ?? fallbackID,
            kind: .message,
            role: role,
            text: text,
            attachments: object.array("attachments").compactMap { value in
                guard let item = value.objectValue,
                      let id = item.string("id"), let kind = item.string("kind"),
                      let name = item.string("displayName"), let mime = item.string("mimeType"),
                      let bytes = item.int64("sizeBytes") else { return nil }
                return ChatAttachmentMetadata(id: id, kind: kind, displayName: name, mimeType: mime, sizeBytes: bytes)
            },
            timestamp: object.int64("timestamp")
        )
    }

    static func reasoning(_ object: [String: JSONValue]) -> ChatReasoningOption? {
        guard let id = object.string("id"), let label = object.string("label") else { return nil }
        return ChatReasoningOption(id: id, label: label)
    }

    static func model(_ object: [String: JSONValue]) -> ChatModelOption? {
        guard let id = object.string("id"), let label = object.string("label") else { return nil }
        let options = object["reasoningOptions"]?.arrayValue?.compactMap { $0.objectValue.flatMap(reasoning) }
        return ChatModelOption(
            id: id, label: label, provider: object.string("provider"), harnessID: object.string("harnessId"),
            harnessLabel: object.string("harnessLabel"), modelID: object.string("modelId"),
            contextWindow: object.int64("contextWindow"), available: object.bool("available"),
            reasoningOptions: options, defaultReasoningEffort: object.string("defaultReasoningEffort")
        )
    }

    static func session(_ object: [String: JSONValue]) -> ChatSession? {
        guard let key = object.string("key") else { return nil }
        return ChatSession(
            key: key, sessionID: object.string("sessionId"), label: object.string("label"), displayName: object.string("displayName"),
            harnessID: object.string("harnessId"), harnessLabel: object.string("harnessLabel"),
            workspacePath: object.string("workspacePath"), workspaceName: object.string("workspaceName"),
            threadPath: object.string("threadPath"), preview: object.string("preview"), source: object.string("source"),
            updatedAt: object.int64("updatedAt"), model: object.string("model"), contextTokens: object.int64("contextTokens"),
            totalTokens: object.int64("totalTokens"), estimatedCostUSD: object.number("estimatedCostUsd"), hasActiveRun: object.bool("hasActiveRun")
        )
    }

    static func usage(_ object: [String: JSONValue]) -> ChatUsage {
        ChatUsage(
            inputTokens: object.int64("inputTokens"), outputTokens: object.int64("outputTokens"),
            totalTokens: object.int64("totalTokens"), contextTokens: object.int64("contextTokens"),
            estimatedCostUSD: object.number("estimatedCostUsd")
        )
    }
}

extension Dictionary where Key == String, Value == JSONValue {
    func string(_ key: String) -> String? { self[key]?.stringValue }
    func bool(_ key: String) -> Bool? { self[key]?.boolValue }
    func number(_ key: String) -> Double? { self[key]?.numberValue }
    func int64(_ key: String) -> Int64? { number(key).map(Int64.init) }
    func array(_ key: String) -> [JSONValue] { self[key]?.arrayValue ?? [] }
    func optionalString(_ key: String, preserving prior: String?) -> String? {
        keys.contains(key) ? string(key) : prior
    }
}

private extension String {
    var trimmedNonempty: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
