import XCTest

final class LynkUITests: XCTestCase {
    func testAppLaunchesFullScreenAndOpensSettings() {
        let app = XCUIApplication()
        app.launchArguments.append(contentsOf: ["-ui-testing", "-ui-testing-reset"])
        app.launch()

        XCTAssertTrue(app.staticTexts["Lynk"].waitForExistence(timeout: 5))
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["settings-view"].waitForExistence(timeout: 3))
    }

    func testLocalModeAndModelLibraryAreAvailable() {
        let app = XCUIApplication()
        app.launchArguments.append(contentsOf: ["-ui-testing", "-ui-testing-reset"])
        app.launch()

        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["settings-view"].waitForExistence(timeout: 3))
        app.segmentedControls["run-target-picker"].buttons["Local phone"].tap()
        app.buttons["local-models-link"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["local-models-view"].waitForExistence(timeout: 3))
    }
}
