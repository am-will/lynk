# Lynk for iOS

The native iPhone client lives in `ios/` and targets iOS 17 or newer. It is a full-screen SwiftUI app and deliberately contains no overlay, accessibility-service, screenshot, app-launch, or Android phone-control behavior.

Regenerate the Xcode project after editing `project.yml`:

```sh
cd ios
xcodegen generate
```

Build and test on an available simulator:

```sh
xcodebuild -project Lynk.xcodeproj -scheme Lynk -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO build test
```

Bridge tokens and OpenAI keys are stored in Keychain. Other settings remain in app-local preferences.
