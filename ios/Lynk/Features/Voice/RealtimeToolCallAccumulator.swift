import Foundation

struct RealtimeToolCall: Equatable, Sendable {
    let callID: String
    let itemID: String?
    let name: String
    let arguments: [String: JSONValue]
}

struct RealtimeToolCallAccumulator: Sendable {
    private struct Pending: Sendable {
        let callID: String
        let itemID: String?
        var name = ""
        var arguments = ""
    }

    private var pending: [String: Pending] = [:]
    private var completed: Set<String> = []

    mutating func reset() {
        pending.removeAll()
        completed.removeAll()
    }

    mutating func apply(_ event: [String: JSONValue]) -> RealtimeToolCall? {
        let type = event.string("type") ?? ""
        let item = event["item"]?.objectValue
        guard type.contains("function_call") || item?.string("type") == "function_call" else { return nil }
        guard let callID = event.string("call_id") ?? event.string("callId") ?? item?.string("call_id") else { return nil }
        let itemID = event.string("item_id") ?? event.string("itemId") ?? item?.string("id")
        let key = itemID ?? callID
        guard !completed.contains(key) else { return nil }
        var call = pending[key] ?? Pending(callID: callID, itemID: itemID)
        if let name = event.string("name") { call.name = name }
        if let delta = event.string("delta") { call.arguments += delta }
        if let itemName = item?.string("name") { call.name = itemName }
        if let full = item?.string("arguments"), !full.isEmpty { call.arguments = full }
        pending[key] = call

        guard type.hasSuffix(".done") || type == "response.output_item.done" else { return nil }
        if let full = event.string("arguments"), !full.isEmpty { call.arguments = full }
        guard !call.name.isEmpty else { return nil }
        let arguments: [String: JSONValue]
        if let data = call.arguments.data(using: .utf8),
           let parsed = try? JSONDecoder().decode([String: JSONValue].self, from: data) {
            arguments = parsed
        } else {
            arguments = ["instruction": .string(call.arguments)]
        }
        pending[key] = nil
        completed.insert(key)
        return RealtimeToolCall(callID: call.callID, itemID: call.itemID, name: call.name, arguments: arguments)
    }
}
