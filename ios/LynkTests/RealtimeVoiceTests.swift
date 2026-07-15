import Foundation
import XCTest
@testable import Lynk

@MainActor
final class RealtimeVoiceTests: XCTestCase {
    func testToolAccumulatorCombinesFragmentsAndDeduplicatesCompletion() {
        var accumulator = RealtimeToolCallAccumulator()
        XCTAssertNil(accumulator.apply([
            "type": .string("response.function_call_arguments.delta"),
            "call_id": .string("call-1"),
            "item_id": .string("item-1"),
            "name": .string("delegate_agent_task"),
            "delta": .string("{\"instruction\":\"Ship")
        ]))
        let completed: [String: JSONValue] = [
            "type": .string("response.function_call_arguments.done"),
            "call_id": .string("call-1"),
            "item_id": .string("item-1"),
            "name": .string("delegate_agent_task"),
            "delta": .string(" it\"}")
        ]
        XCTAssertEqual(accumulator.apply(completed)?.arguments["instruction"], .string("Ship it"))
        XCTAssertNil(accumulator.apply(completed))
    }

    func testTranscriptNormalizerHandlesCumulativeDeltaAndFinal() {
        var normalizer = RealtimeTranscriptNormalizer()
        _ = normalizer.apply(["type": .string("response.audio_transcript.delta"), "item_id": .string("a"), "delta": .string("Hel")])
        _ = normalizer.apply(["type": .string("response.audio_transcript.delta"), "item_id": .string("a"), "delta": .string("Hello")])
        let final = normalizer.apply(["type": .string("response.audio_transcript.done"), "item_id": .string("a"), "transcript": .string("Hello there"), "isFinal": .bool(true)])
        XCTAssertEqual(final, "Lynk: Hello there")
    }

    func testStartUsesIOSPromptAndNoPhoneFields() async throws {
        let transport = MockRealtimeTransport()
        let wire = WireSpy()
        let controller = controller(transports: [transport], wire: wire)
        let bridge = BridgeClient()
        bridge.phase = .registered
        let chat = ChatStore()
        chat.selectedModel = "codex:gpt-test"

        await controller.start(settings: settings(), chat: chat, bridge: bridge)

        let start = try XCTUnwrap(wire.messages.first)
        XCTAssertEqual(start.string("type"), "realtime.start")
        XCTAssertEqual(start.string("model"), "codex:gpt-test")
        XCTAssertFalse((start.string("systemPrompt") ?? "").localizedCaseInsensitiveContains("Android"))
        XCTAssertNil(start["location"])
        XCTAssertNil(start["phoneControl"])
    }

    func testOldSDPIsIgnoredAfterRestart() async throws {
        let first = MockRealtimeTransport()
        let second = MockRealtimeTransport()
        let wire = WireSpy()
        let controller = controller(transports: [first, second], wire: wire)
        let bridge = BridgeClient()
        bridge.phase = .registered
        let chat = ChatStore()

        await controller.start(settings: settings(), chat: chat, bridge: bridge)
        let oldID = try XCTUnwrap(wire.messages.first?.string("voiceSessionId"))
        controller.stop()
        await Task.yield()
        await controller.start(settings: settings(), chat: chat, bridge: bridge)
        let newID = try XCTUnwrap(wire.messages.last(where: { $0.string("type") == "realtime.start" })?.string("voiceSessionId"))

        controller.receive(["type": .string("realtime.sdp"), "voiceSessionId": .string(oldID), "sdp": .string("old")])
        controller.receive(["type": .string("realtime.sdp"), "voiceSessionId": .string(newID), "sdp": .string("new")])
        await Task.yield()
        XCTAssertTrue(first.answers.isEmpty)
        XCTAssertEqual(second.answers, ["new"])
    }

    func testForgedPhoneToolCallFailsLocallyAndNeverReachesBridge() async throws {
        let transport = MockRealtimeTransport()
        let wire = WireSpy()
        let controller = controller(transports: [transport], wire: wire)
        let bridge = BridgeClient()
        bridge.phase = .registered
        await controller.start(settings: settings(), chat: ChatStore(), bridge: bridge)
        let wireCount = wire.messages.count
        let event: [String: JSONValue] = [
            "type": .string("response.function_call_arguments.done"),
            "call_id": .string("phone-call"),
            "name": .string("run_phone_task"),
            "arguments": .string("{\"instruction\":\"Open Settings\"}")
        ]
        let data = try JSONEncoder().encode(event)
        transport.onEvent?(try XCTUnwrap(String(data: data, encoding: .utf8)))
        await Task.yield()

        XCTAssertEqual(wire.messages.count, wireCount)
        XCTAssertTrue(transport.sentEvents.contains { $0.string("type") == "conversation.item.create" })
        XCTAssertFalse(BridgeRegistration.containsPhoneControlCapability())
    }

    func testSecretsOnlyTravelOverLoopbackOrTLS() {
        XCTAssertTrue(RealtimeVoiceController.mayTransmitSecret(to: URL(string: "ws://127.0.0.1:8788/phone")))
        XCTAssertTrue(RealtimeVoiceController.mayTransmitSecret(to: URL(string: "wss://lynk.example/phone")))
        XCTAssertFalse(RealtimeVoiceController.mayTransmitSecret(to: URL(string: "ws://192.168.1.8:8788/phone")))
    }

    private func controller(transports: [MockRealtimeTransport], wire: WireSpy) -> RealtimeVoiceController {
        var queue = transports
        return RealtimeVoiceController(
            transportFactory: { queue.removeFirst() },
            permissionProvider: { true },
            audioSessionConfigurator: {},
            audioSessionDeactivator: {},
            wireSend: { payload in wire.messages.append(payload); return true }
        )
    }

    private func settings() -> SettingsSnapshot {
        SettingsSnapshot(
            bridgeURLs: ["ws://127.0.0.1:8788/phone"],
            deviceID: "lynk-ios-test",
            token: "token",
            openAIKey: "sk-test",
            systemPrompt: SettingsStore.defaultSystemPrompt,
            runTarget: .host,
            selectedLocalModelID: nil
        )
    }
}

@MainActor
private final class WireSpy {
    var messages: [[String: JSONValue]] = []
}

@MainActor
private final class MockRealtimeTransport: RealtimeVoiceTransport {
    var onEvent: ((String) -> Void)?
    var onConnectionState: ((String) -> Void)?
    var answers: [String] = []
    var sentEvents: [[String: JSONValue]] = []
    var muted = false
    var closed = false

    func createOffer() async throws -> String { "mock-offer" }
    func applyAnswer(_ sdp: String) async throws { answers.append(sdp) }
    func setMuted(_ muted: Bool) { self.muted = muted }
    func send(event: [String: JSONValue]) -> Bool { sentEvents.append(event); return true }
    func close() { closed = true }
}
