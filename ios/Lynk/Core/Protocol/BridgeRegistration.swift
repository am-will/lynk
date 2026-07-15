import Foundation

enum BridgeRegistration {
    static let platform = "ios"
    static let capabilities = ["chat", "attachments", "transcription"]
    static let forbiddenPhoneCapabilities: Set<String> = [
        "phone_control", "accessibility_tree", "gestures", "text_input", "screenshots", "app_launch"
    ]

    static func payload(deviceID: String, token: String) -> [String: JSONValue] {
        [
            "type": .string("register"),
            "deviceId": .string(deviceID),
            "token": .string(token),
            "platform": .string(platform),
            "capabilities": .array(capabilities.map(JSONValue.string))
        ]
    }

    static func isAcknowledgement(_ message: [String: JSONValue], deviceID: String) -> Bool {
        message["type"]?.stringValue == "agent_status" &&
            message["text"]?.stringValue == "Registered \(deviceID)"
    }

    static func containsPhoneControlCapability(_ capabilities: [String] = capabilities) -> Bool {
        !forbiddenPhoneCapabilities.isDisjoint(with: capabilities)
    }
}

