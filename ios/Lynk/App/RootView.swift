import SwiftUI

struct RootView: View {
    @Environment(BridgeClient.self) private var bridge
    @Environment(SettingsStore.self) private var settings
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()
                Image(systemName: bridge.phase == .registered ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle")
                    .font(.system(size: 58, weight: .light))
                    .foregroundStyle(bridge.phase == .registered ? Color.accentColor : Color.secondary)
                    .accessibilityIdentifier("connection-symbol")
                Text("Lynk")
                    .font(.largeTitle.bold())
                Text(bridge.phase.label)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("connection-status")
                if bridge.phase != .registered {
                    Button("Connect") { bridge.connect(using: settings.snapshot) }
                        .buttonStyle(.borderedProminent)
                        .disabled(settings.snapshot.token.isEmpty)
                }
                Spacer()
                Text("Native iPhone chat client")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding()
            .background(Color(.systemBackground))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Settings", systemImage: "gearshape") { showingSettings = true }
                        .accessibilityIdentifier("settings-button")
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }
}

#Preview {
    RootView()
        .environment(BridgeClient())
        .environment(SettingsStore())
}

