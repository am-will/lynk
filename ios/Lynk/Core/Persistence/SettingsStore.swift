import Foundation
import Observation

enum RunTarget: String, CaseIterable, Identifiable, Sendable {
    case host = "Host bridge"
    case local = "Local phone"
    var id: String { rawValue }
}

struct SettingsSnapshot: Equatable, Sendable {
    let bridgeURLs: [String]
    let deviceID: String
    let token: String
    let openAIKey: String
    let systemPrompt: String
    let runTarget: RunTarget
    let selectedLocalModelID: String?
}

@MainActor
@Observable
final class SettingsStore {
    static let defaultSystemPrompt = """
    You're accessed through the native Lynk iOS app.

    Help with chat, coding, research, and agent tasks using only tools explicitly available in the selected host harness.

    Do not assume device-control, screen-observation, app-launch, shell, or unrestricted filesystem capabilities.

    Keep status and final responses concise without leaving important details out.
    """

    private enum Key {
        static let bridgeURLs = "bridgeURLs"
        static let deviceID = "deviceID"
        static let systemPrompt = "systemPrompt"
        static let token = "bridgeToken"
        static let openAIKey = "openAIKey"
        static let runTarget = "runTarget"
        static let selectedLocalModelID = "selectedLocalModelID"
    }

    private let defaults: UserDefaults
    private let keychain: KeychainStore

    var bridgeURLsText: String { didSet { defaults.set(bridgeURLsText, forKey: Key.bridgeURLs) } }
    var deviceID: String { didSet { defaults.set(deviceID, forKey: Key.deviceID) } }
    var token: String { didSet { keychain.set(token, for: Key.token) } }
    var openAIKey: String { didSet { keychain.set(openAIKey, for: Key.openAIKey) } }
    var systemPrompt: String { didSet { defaults.set(systemPrompt, forKey: Key.systemPrompt) } }
    var runTarget: RunTarget { didSet { defaults.set(runTarget.rawValue, forKey: Key.runTarget) } }
    var selectedLocalModelID: String { didSet { defaults.set(selectedLocalModelID, forKey: Key.selectedLocalModelID) } }

    init(defaults: UserDefaults = .standard, keychain: KeychainStore = KeychainStore()) {
        self.defaults = defaults
        self.keychain = keychain
        if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset") {
            [Key.bridgeURLs, Key.deviceID, Key.systemPrompt, Key.runTarget, Key.selectedLocalModelID]
                .forEach(defaults.removeObject(forKey:))
            keychain.set("", for: Key.token)
            keychain.set("", for: Key.openAIKey)
        }
        bridgeURLsText = defaults.string(forKey: Key.bridgeURLs) ?? "ws://127.0.0.1:8788/phone"
        deviceID = defaults.string(forKey: Key.deviceID) ?? "lynk-ios-\(UUID().uuidString.lowercased().prefix(8))"
        token = keychain.string(for: Key.token)
        openAIKey = keychain.string(for: Key.openAIKey)
        systemPrompt = defaults.string(forKey: Key.systemPrompt).flatMap { $0.isEmpty ? nil : $0 } ?? Self.defaultSystemPrompt
        runTarget = defaults.string(forKey: Key.runTarget).flatMap(RunTarget.init(rawValue:)) ?? .host
        selectedLocalModelID = defaults.string(forKey: Key.selectedLocalModelID) ?? ""
        defaults.set(deviceID, forKey: Key.deviceID)
    }

    var snapshot: SettingsSnapshot {
        SettingsSnapshot(
            bridgeURLs: bridgeURLsText.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty },
            deviceID: deviceID.trimmingCharacters(in: .whitespacesAndNewlines),
            token: token.trimmingCharacters(in: .whitespacesAndNewlines),
            openAIKey: openAIKey.trimmingCharacters(in: .whitespacesAndNewlines),
            systemPrompt: systemPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Self.defaultSystemPrompt : systemPrompt,
            runTarget: runTarget,
            selectedLocalModelID: selectedLocalModelID.isEmpty ? nil : selectedLocalModelID
        )
    }
}
