import SwiftUI

struct VoiceView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RealtimeVoiceController.self) private var voice
    @Environment(SettingsStore.self) private var settings
    @Environment(ChatStore.self) private var chat
    @Environment(BridgeClient.self) private var bridge

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer(minLength: 10)
                ZStack {
                    Circle()
                        .fill(statusColor.opacity(0.15))
                        .frame(width: 150, height: 150)
                    Image(systemName: voice.status == .speaking ? "waveform.circle.fill" : "waveform.circle")
                        .font(.system(size: 82, weight: .light))
                        .foregroundStyle(statusColor)
                        .symbolEffect(.variableColor.iterative, isActive: voice.isActive)
                }
                VStack(spacing: 6) {
                    Text(voice.status.rawValue).font(.title2.bold())
                    if let error = voice.error {
                        Text(error).font(.callout).foregroundStyle(.red).multilineTextAlignment(.center)
                    } else if let task = voice.currentTask {
                        Text(task).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        if voice.queuedTasks > 0 { Text("\(voice.queuedTasks) queued").font(.caption) }
                    } else {
                        Text("Speak naturally. Lynk can delegate general work to the selected host agent.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal)

                ScrollView {
                    Text(voice.transcript.isEmpty ? "Your conversation transcript will appear here." : voice.transcript)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(voice.transcript.isEmpty ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding()
                }
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                .padding(.horizontal)

                HStack(spacing: 28) {
                    Button {
                        voice.toggleMute()
                    } label: {
                        Label(voice.isMuted ? "Unmute" : "Mute", systemImage: voice.isMuted ? "mic.slash.fill" : "mic.fill")
                            .frame(minWidth: 84)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!voice.isActive)

                    Button(role: .destructive) {
                        voice.stop(reason: "User ended the realtime voice session")
                        dismiss()
                    } label: {
                        Label("Hang up", systemImage: "phone.down.fill").frame(minWidth: 100)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(.bottom)
            }
            .navigationTitle("Realtime Voice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        if voice.isActive { voice.stop(reason: "Voice screen closed") }
                        dismiss()
                    }
                }
            }
        }
        .interactiveDismissDisabled(voice.isActive)
        .task {
            if !voice.isActive {
                await voice.start(settings: settings.snapshot, chat: chat, bridge: bridge)
            }
        }
    }

    private var statusColor: Color {
        switch voice.status {
        case .error: .red
        case .connecting, .thinking: .orange
        case .speaking: .purple
        case .listening: .green
        case .idle: .secondary
        }
    }
}
