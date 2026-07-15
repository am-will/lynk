import XCTest
@testable import Lynk

@MainActor
final class BridgeRegistrationTests: XCTestCase {
    func testIOSRegistrationExcludesPhoneControl() {
        XCTAssertEqual(BridgeRegistration.platform, "ios")
        XCTAssertFalse(BridgeRegistration.containsPhoneControlCapability())
        let payload = BridgeRegistration.payload(deviceID: "iphone", token: "secret")
        XCTAssertEqual(payload["platform"], .string("ios"))
    }

    func testRegistrationRequiresExactAcknowledgement() {
        XCTAssertTrue(BridgeRegistration.isAcknowledgement([
            "type": .string("agent_status"),
            "text": .string("Registered iphone")
        ], deviceID: "iphone"))
        XCTAssertFalse(BridgeRegistration.isAcknowledgement([
            "type": .string("agent_status"),
            "text": .string("Registered iphone with extras")
        ], deviceID: "iphone"))
    }

    func testIOSDefaultPromptContainsNoPhoneControlInstructions() {
        let prompt = SettingsStore.defaultSystemPrompt
        XCTAssertFalse(prompt.localizedCaseInsensitiveContains("Android"))
        XCTAssertFalse(prompt.localizedCaseInsensitiveContains("Phone Control MCP"))
        XCTAssertFalse(prompt.localizedCaseInsensitiveContains("android-phone"))
    }
}
