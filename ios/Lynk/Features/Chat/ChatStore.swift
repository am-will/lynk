import Foundation
import Observation

@MainActor
@Observable
final class ChatStore {
    var status: String?
    var lastMessageType: String?

    func receive(_ message: [String: JSONValue]) {
        lastMessageType = message["type"]?.stringValue
        if message["type"]?.stringValue == "agent_status" {
            status = message["text"]?.stringValue
        }
    }
}

