import Foundation

struct RealtimeTranscriptNormalizer: Sendable {
    private struct Entry: Sendable {
        let id: String
        var role: String
        var text: String
        var isFinal: Bool
    }

    private var entries: [Entry] = []

    mutating func reset() { entries.removeAll() }

    mutating func apply(_ event: [String: JSONValue]) -> String {
        let type = event.string("type") ?? ""
        if type == "input_audio_buffer.speech_started" {
            entries.removeAll { $0.role == "user" && !$0.isFinal }
            return displayText
        }
        guard let text = eventText(event), !text.isEmpty else { return displayText }
        let role = event.string("role") ?? inferredRole(type: type, item: event["item"]?.objectValue)
        let item = event["item"]?.objectValue
        let id = event.string("itemId") ?? event.string("item_id") ?? event.string("id")
            ?? event.string("response_id") ?? item?.string("id") ?? "\(role)-current"
        let final = event.bool("isFinal") == true || event.bool("final") == true
            || type.hasSuffix(".done") || type.hasSuffix(".completed")
        if let index = entries.firstIndex(where: { $0.id == id }) {
            entries[index].text = merge(existing: entries[index].text, incoming: text, final: final)
            entries[index].role = role
            entries[index].isFinal = entries[index].isFinal || final
        } else {
            entries.append(Entry(id: id, role: role, text: text, isFinal: final))
        }
        return displayText
    }

    var displayText: String {
        entries.filter { !$0.text.isEmpty }.map {
            "\($0.role == "user" ? "You" : "Lynk"): \($0.text)"
        }.joined(separator: "\n\n")
    }

    private func eventText(_ event: [String: JSONValue]) -> String? {
        if let value = event.string("text") ?? event.string("delta") ?? event.string("transcript") { return value }
        guard let item = event["item"]?.objectValue else { return nil }
        if let value = item.string("text") ?? item.string("transcript") { return value }
        return item.array("content").compactMap { part in
            part.objectValue.flatMap { $0.string("text") ?? $0.string("transcript") }
        }.joined()
    }

    private func inferredRole(type: String, item: [String: JSONValue]?) -> String {
        if let role = item?.string("role") { return role }
        return type.contains("input_audio") ? "user" : "assistant"
    }

    private func merge(existing: String, incoming: String, final: Bool) -> String {
        if incoming == existing { return existing }
        if incoming.hasPrefix(existing) { return incoming }
        if final, existing.hasPrefix(incoming) { return existing }
        let maximumOverlap = min(existing.count, incoming.count)
        for count in stride(from: maximumOverlap, through: 1, by: -1) {
            if existing.suffix(count) == incoming.prefix(count) {
                return existing + incoming.dropFirst(count)
            }
        }
        return existing + incoming
    }
}
