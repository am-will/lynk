import XCTest
@testable import Lynk

@MainActor
final class ChatStoreTests: XCTestCase {
    func testExplicitNullStatusClearsWhileOmissionPreserves() {
        let store = ChatStore()
        store.receive(state(status: .string("Working")))
        XCTAssertEqual(store.status, "Working")

        store.receive(state(status: nil))
        XCTAssertEqual(store.status, "Working")

        store.receive(state(status: .null))
        XCTAssertNil(store.status)
    }

    func testStreamingFinalReplacesAssistantAndClearsReasoning() {
        let store = ChatStore()
        store.receive(state(status: nil))
        store.receive([
            "type": .string("chat.reasoning_delta"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "runId": .string("run"), "delta": .string("thinking")
        ])
        store.receive([
            "type": .string("chat.delta"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "runId": .string("run"), "delta": .string("Hel")
        ])
        store.receive([
            "type": .string("chat.delta"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "runId": .string("run"), "delta": .string("lo")
        ])
        store.receive([
            "type": .string("chat.final"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "runId": .string("run"), "text": .string("Hello!")
        ])

        XCTAssertEqual(store.timeline.count, 1)
        XCTAssertEqual(store.timeline.first?.text, "Hello!")
        XCTAssertFalse(store.isRunning)
    }

    func testUnknownHarnessModelRemainsDynamic() {
        let store = ChatStore()
        store.receive([
            "type": .string("chat.models"), "deviceId": .string("iphone"),
            "models": .array([.object([
                "id": .string("future:model-a"), "label": .string("Model A"),
                "harnessId": .string("future"), "harnessLabel": .string("Future Harness")
            ])]),
            "reasoningOptions": .array([])
        ])

        XCTAssertEqual(store.models.first?.id, "future:model-a")
        XCTAssertEqual(store.models.first?.groupLabel, "Future Harness")
    }

    func testToolUpsertPreservesOmittedOutputAndActions() {
        let store = ChatStore()
        store.receive(state(status: nil))
        store.receive(tool(status: "blocked", includeDetails: true))
        store.toggleTool("event")
        store.receive(tool(status: "completed", includeDetails: false))

        let tool = store.timeline.first?.tool
        XCTAssertEqual(tool?.output, .string("pending"))
        XCTAssertEqual(tool?.actions.first?.id, "opaque-option")
        XCTAssertEqual(tool?.isExpanded, true)
    }

    private func state(status: JSONValue?) -> [String: JSONValue] {
        var message: [String: JSONValue] = [
            "type": .string("chat.state"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "isRunning": .bool(false)
        ]
        if let status { message["status"] = status }
        return message
    }

    private func tool(status: String, includeDetails: Bool) -> [String: JSONValue] {
        var message: [String: JSONValue] = [
            "type": .string("chat.tool_event"), "deviceId": .string("iphone"),
            "sessionKey": .string("session"), "runId": .string("run"),
            "eventId": .string("event"), "toolName": .string("permission"),
            "title": .string("Permission"), "status": .string(status)
        ]
        if includeDetails {
            message["output"] = .string("pending")
            message["actions"] = .array([.object([
                "id": .string("opaque-option"), "label": .string("Allow"),
                "command": .string("permission.respond"), "args": .object(["optionId": .string("opaque-option")])
            ])])
        }
        return message
    }
}
