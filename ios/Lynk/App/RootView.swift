import SwiftUI

struct RootView: View {
    @Environment(BridgeClient.self) private var bridge
    @Environment(SettingsStore.self) private var settings
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if bridge.phase != .registered {
                    ConnectionBanner(
                        label: bridge.phase.label,
                        canConnect: !settings.snapshot.token.isEmpty,
                        connect: { bridge.connect(using: settings.snapshot) }
                    )
                }
                ChatView(showingSettings: $showingSettings)
            }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Settings", systemImage: "gearshape") { showingSettings = true }
                            .accessibilityIdentifier("settings-button")
                    }
                }
        }
        .sheet(isPresented: $showingSettings) { SettingsView() }
    }
}

private struct ConnectionBanner: View {
    let label: String
    let canConnect: Bool
    let connect: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "bolt.horizontal.circle")
            Text(label).lineLimit(1)
            Spacer()
            Button("Connect", action: connect).disabled(!canConnect)
        }
        .font(.subheadline)
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityIdentifier("connection-status")
    }
}

#Preview {
    RootView()
        .environment(BridgeClient())
        .environment(SettingsStore())
        .environment(ChatStore())
}
