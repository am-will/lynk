import Foundation
import Observation

@MainActor
@Observable
final class AppContainer {
    let settings: SettingsStore
    let bridge: BridgeClient
    let chat: ChatStore
    let voice: RealtimeVoiceController
    let localModels: LocalModelStore
    let localInference: LocalInferenceController

    init() {
        let settings = SettingsStore()
        let chat = ChatStore()
        let bridge = BridgeClient()
        let voice = RealtimeVoiceController()
        let localModels = LocalModelStore()
        let localInference = LocalInferenceController()
        self.settings = settings
        self.chat = chat
        self.bridge = bridge
        self.voice = voice
        self.localModels = localModels
        self.localInference = localInference
        bridge.onMessage = { [weak chat, weak voice] message in
            chat?.receive(message)
            voice?.receive(message)
        }
    }

    func start() {
        guard
            !ProcessInfo.processInfo.arguments.contains("-ui-testing"),
            settings.runTarget == .host
        else { return }
        bridge.connect(using: settings.snapshot)
    }
}
