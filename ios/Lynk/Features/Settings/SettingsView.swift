import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SettingsStore.self) private var settings
    @Environment(BridgeClient.self) private var bridge

    var body: some View {
        @Bindable var settings = settings
        NavigationStack {
            Form {
                Section("Run on") {
                    Picker("Runtime", selection: $settings.runTarget) {
                        ForEach(RunTarget.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("run-target-picker")
                    NavigationLink("Local models") { LocalModelsView() }
                        .accessibilityIdentifier("local-models-link")
                    if settings.runTarget == .local {
                        Text("Local mode is chat-only. It never provides a desktop shell, unrestricted files, screenshots, app control, or Android phone tools.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Connection") {
                    TextField("ws://127.0.0.1:8788/phone", text: $settings.bridgeURLsText, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Device ID", text: $settings.deviceID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Pairing token", text: $settings.token)
                        .textInputAutocapitalization(.never)
                }
                Section("Voice") {
                    SecureField("OpenAI API key", text: $settings.openAIKey)
                        .textInputAutocapitalization(.never)
                    Text("Stored in this device's Keychain.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("Agent instructions") {
                    TextEditor(text: $settings.systemPrompt)
                        .frame(minHeight: 150)
                    Button("Restore iOS default") { settings.systemPrompt = SettingsStore.defaultSystemPrompt }
                }
                Section {
                    Button("Reconnect") { bridge.connect(using: settings.snapshot) }
                }
            }
            .accessibilityIdentifier("settings-view")
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
