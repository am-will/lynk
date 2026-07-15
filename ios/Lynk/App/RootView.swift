import SwiftUI

struct RootView: View {
    @Environment(BridgeClient.self) private var bridge
    @Environment(SettingsStore.self) private var settings
    @Environment(RealtimeVoiceController.self) private var voice
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingSettings = false
    @State private var showingVoice = false

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
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Voice", systemImage: "waveform.circle") { showingVoice = true }
                            .disabled(bridge.phase != .registered)
                            .accessibilityIdentifier("voice-button")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Settings", systemImage: "gearshape") { showingSettings = true }
                            .accessibilityIdentifier("settings-button")
                    }
                }
        }
        .sheet(isPresented: $showingSettings) { SettingsView() }
        .fullScreenCover(isPresented: $showingVoice) { VoiceView() }
        .onChange(of: scenePhase) {
            if scenePhase != .active, voice.isActive { voice.stop(reason: "Lynk left the foreground") }
        }
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
        .environment(RealtimeVoiceController())
}
