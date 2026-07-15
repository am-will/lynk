import SwiftUI

@main
struct LynkApp: App {
    @State private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container.settings)
                .environment(container.bridge)
                .environment(container.chat)
                .task { container.start() }
        }
    }
}

