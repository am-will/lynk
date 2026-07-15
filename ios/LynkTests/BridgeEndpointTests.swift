import XCTest
@testable import Lynk

final class BridgeEndpointTests: XCTestCase {
    func testAddsPhonePathAndDerivesHTTPBase() throws {
        let endpoint = try BridgeEndpoint("ws://127.0.0.1:8788")
        XCTAssertEqual(endpoint.webSocketURL.absoluteString, "ws://127.0.0.1:8788/phone")
        XCTAssertEqual(endpoint.httpBaseURL.absoluteString, "http://127.0.0.1:8788")
    }

    func testRejectsHTTPTransport() {
        XCTAssertThrowsError(try BridgeEndpoint("http://example.com"))
    }
}

