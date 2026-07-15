import Foundation
import Observation

@MainActor
@Observable
final class AppContainer {
    let settings: SettingsStore
    let bridge: BridgeClient
    let chat: ChatStore

    init() {
        let settings = SettingsStore()
        let chat = ChatStore()
        let bridge = BridgeClient()
        self.settings = settings
        self.chat = chat
        self.bridge = bridge
        bridge.onMessage = { [weak chat] message in
            chat?.receive(message)
        }
    }

    func start() {
        guard !ProcessInfo.processInfo.arguments.contains("-ui-testing") else { return }
        bridge.connect(using: settings.snapshot)
    }
}

