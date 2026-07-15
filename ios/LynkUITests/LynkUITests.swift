import XCTest

final class LynkUITests: XCTestCase {
    func testAppLaunchesFullScreenAndOpensSettings() {
        let app = XCUIApplication()
        app.launchArguments.append("-ui-testing")
        app.launch()

        XCTAssertTrue(app.staticTexts["Lynk"].waitForExistence(timeout: 5))
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["settings-view"].waitForExistence(timeout: 3))
    }
}
