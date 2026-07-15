import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    @Binding var showingSettings: Bool
    @Environment(ChatStore.self) private var chat
    @Environment(BridgeClient.self) private var bridge
    @Environment(SettingsStore.self) private var settings
    @Environment(LocalModelStore.self) private var localModels
    @Environment(LocalInferenceController.self) private var localInference
    @State private var draft = ""
    @State private var activeSendMode = ActiveSendMode.steer
    @State private var showingModels = false
    @State private var showingSessions = false
    @State private var showingNewSession = false
    @State private var showingFileImporter = false
    @State private var attachments = AttachmentStore()
    @State private var transcription = TranscriptionController()

    var body: some View {
        VStack(spacing: 0) {
            ChatContextBar(
                chat: chat,
                deviceID: settings.snapshot.deviceID,
                bridge: bridge,
                isLocal: settings.runTarget == .local,
                localModelName: selectedLocalModel?.displayName,
                showModels: {
                    if settings.runTarget == .local { showingSettings = true }
                    else { showingModels = true }
                },
                showSessions: { showingSessions = true }
            )
            Divider()
            TimelineView(chat: chat, deviceID: settings.snapshot.deviceID, bridge: bridge)
            if let ratio = chat.usage.contextRatio {
                UsageBar(usage: chat.usage, ratio: ratio)
            }
            if let error = chat.error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.top, 6)
            }
            CommandSuggestions(draft: $draft, commands: chat.commands)
            AttachmentTray(store: attachments)
            TranscriptionStatus(controller: transcription)
            ComposerView(
                draft: $draft,
                isRunning: chat.isRunning,
                isBusy: attachments.isImporting || attachments.isUploading,
                hasAttachments: !attachments.pending.isEmpty,
                transcription: transcription,
                sendMode: $activeSendMode,
                attach: { showingFileImporter = true },
                transcribe: toggleTranscription,
                cancelTranscription: { transcription.cancel() },
                send: send,
                stop: {
                    if settings.runTarget == .local { localInference.stop(chat: chat) }
                    else { chat.stop(deviceID: settings.snapshot.deviceID, bridge: bridge) }
                }
            )
        }
        .navigationTitle(settings.runTarget == .local ? "Local phone" : (chat.harnessLabel ?? "Lynk"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingModels) {
            ModelPickerView(chat: chat, deviceID: settings.snapshot.deviceID, bridge: bridge)
        }
        .sheet(isPresented: $showingSessions) {
            SessionPickerView(
                chat: chat,
                deviceID: settings.snapshot.deviceID,
                bridge: bridge,
                newSession: { showingSessions = false; showingNewSession = true }
            )
        }
        .sheet(isPresented: $showingNewSession) {
            NewSessionView(chat: chat, deviceID: settings.snapshot.deviceID, bridge: bridge)
        }
        .fileImporter(
            isPresented: $showingFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case let .success(urls): Task { await attachments.importFiles(urls) }
            case let .failure(error): attachments.error = error.localizedDescription
            }
        }
        .onDisappear { if transcription.isRecording { transcription.cancel() } }
    }

    private func send() {
        let text = draft
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.pending.isEmpty else { return }
        if settings.runTarget == .local {
            guard attachments.pending.isEmpty else {
                attachments.error = "Host attachment uploads are unavailable in local mode."
                return
            }
            draft = ""
            localInference.generate(
                text: text,
                systemPrompt: settings.snapshot.systemPrompt,
                selectedModelID: settings.snapshot.selectedLocalModelID,
                models: localModels,
                chat: chat
            )
            return
        }
        draft = ""
        Task {
            do {
                let snapshot = settings.snapshot
                let sessionKey = chat.sessionKey
                let connectionGeneration = bridge.connectionGeneration
                let references = try await attachments.uploadAll(
                    endpoint: bridge.endpoint.map { BridgeEndpoint(webSocketURL: $0) },
                    snapshot: snapshot,
                    sessionKey: sessionKey
                )
                guard
                    bridge.phase == .registered,
                    bridge.connectionGeneration == connectionGeneration,
                    chat.sessionKey == sessionKey
                else { throw AttachmentError.bridgeUnavailable }
                let sent = await chat.send(
                    text: text,
                    attachments: references,
                    delivery: activeSendMode,
                    systemPrompt: snapshot.systemPrompt,
                    deviceID: snapshot.deviceID,
                    bridge: bridge
                )
                if sent { attachments.removeUploaded(references) }
            } catch {
                attachments.error = error.localizedDescription
                if draft.isEmpty { draft = text }
            }
        }
    }

    private var selectedLocalModel: ImportedLocalModel? {
        guard let id = settings.snapshot.selectedLocalModelID else { return nil }
        return localModels.models.first { $0.id == id }
    }

    private func toggleTranscription() {
        if transcription.isRecording {
            Task { await transcription.stopAndTranscribe() }
        } else {
            let key = settings.snapshot.openAIKey
            Task {
                await transcription.start(apiKey: key) { transcript in
                    draft = [draft.trimmingCharacters(in: .whitespacesAndNewlines), transcript]
                        .filter { !$0.isEmpty }
                        .joined(separator: " ")
                }
            }
        }
    }
}

private struct ChatContextBar: View {
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient
    let isLocal: Bool
    let localModelName: String?
    let showModels: () -> Void
    let showSessions: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: showModels) {
                Label(isLocal ? (localModelName ?? "Choose local model") : (chat.selectedModelOption?.label ?? chat.selectedModel ?? "Choose model"), systemImage: "cpu")
                    .lineLimit(1)
            }
            .buttonStyle(.bordered)
            Button(action: showSessions) {
                HStack(spacing: 5) {
                    Image(systemName: "bubble.left.and.bubble.right")
                    Text(chat.sessions.first(where: { $0.key == chat.sessionKey })?.title ?? "Sessions")
                        .lineLimit(1)
                    let unread = chat.unreadReplies.values.reduce(0) { $0 + $1.count }
                    if unread > 0 { Text("\(unread)").font(.caption.bold()).foregroundStyle(.red) }
                }
            }
            .buttonStyle(.bordered)
            .disabled(isLocal)
            Spacer(minLength: 0)
            Menu {
                ForEach(chat.effectiveReasoningOptions) { option in
                    Button {
                        chat.setReasoning(option.id, deviceID: deviceID, bridge: bridge)
                    } label: {
                        if option.id == chat.reasoningEffort { Label(option.label, systemImage: "checkmark") }
                        else { Text(option.label) }
                    }
                }
            } label: {
                Image(systemName: "brain.head.profile")
                    .frame(width: 28, height: 28)
            }
            .disabled(isLocal || chat.effectiveReasoningOptions.isEmpty)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }
}

private struct UsageBar: View {
    let usage: ChatUsage
    let ratio: Double

    var body: some View {
        VStack(spacing: 3) {
            ProgressView(value: ratio)
                .tint(ratio > 0.9 ? .red : ratio > 0.7 ? .orange : .accentColor)
            HStack {
                Text("Context \((ratio * 100).formatted(.number.precision(.fractionLength(0))))%")
                Spacer()
                if let total = usage.totalTokens { Text("\(total.formatted()) tokens") }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(.horizontal)
        .padding(.top, 5)
    }
}

private struct ComposerView: View {
    @Binding var draft: String
    let isRunning: Bool
    let isBusy: Bool
    let hasAttachments: Bool
    let transcription: TranscriptionController
    @Binding var sendMode: ActiveSendMode
    let attach: () -> Void
    let transcribe: () -> Void
    let cancelTranscription: () -> Void
    let send: () -> Void
    let stop: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            if isRunning {
                Picker("Active send", selection: $sendMode) {
                    ForEach(ActiveSendMode.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
            }
            HStack(alignment: .bottom, spacing: 10) {
                if transcription.isRecording {
                    Button(action: cancelTranscription) {
                        Image(systemName: "xmark").frame(width: 28, height: 38)
                    }
                    .tint(.red)
                    .accessibilityLabel("Cancel transcription")
                }
                Button(action: transcribe) {
                    if transcription.isTranscribing { ProgressView().frame(width: 28, height: 38) }
                    else {
                        Image(systemName: transcription.isRecording ? "stop.circle.fill" : "mic")
                            .frame(width: 28, height: 38)
                    }
                }
                .disabled(isBusy || transcription.isTranscribing)
                .tint(transcription.isRecording ? .red : .accentColor)
                .accessibilityLabel(transcription.isRecording ? "Stop and transcribe" : "Start transcription")
                Button(action: attach) {
                    Image(systemName: "paperclip").frame(width: 28, height: 38)
                }
                .disabled(isBusy || transcription.isRecording || transcription.isTranscribing)
                .accessibilityLabel("Attach files")
                TextField("Message Lynk", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 13)
                    .padding(.vertical, 10)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                    .accessibilityIdentifier("composer")
                    .onSubmit(send)
                if isRunning {
                    Button(action: stop) {
                        Image(systemName: "stop.fill").frame(width: 38, height: 38)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .accessibilityLabel("Stop")
                }
                Button(action: send) {
                    if isBusy { ProgressView().frame(width: 38, height: 38) }
                    else {
                        Image(systemName: isRunning && sendMode == .steer ? "arrow.triangle.turn.up.right.diamond.fill" : "arrow.up")
                            .frame(width: 38, height: 38)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isBusy || (draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !hasAttachments))
                .accessibilityLabel("Send")
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
        .padding(.top, 8)
        .background(.bar)
    }
}

private struct TranscriptionStatus: View {
    let controller: TranscriptionController

    var body: some View {
        if controller.isRecording || controller.isTranscribing || controller.error != nil {
            VStack(alignment: .leading, spacing: 4) {
                if controller.isRecording {
                    ProgressView(value: Double(controller.audioLevel))
                        .tint(.red)
                    Text("Recording for transcription. Stop to review the text, or cancel.")
                } else if controller.isTranscribing {
                    Text("Transcribing…")
                }
                if let error = controller.error { Text(error).foregroundStyle(.red) }
            }
            .font(.caption)
            .padding(.horizontal)
            .padding(.top, 6)
        }
    }
}

private struct AttachmentTray: View {
    let store: AttachmentStore

    var body: some View {
        if !store.pending.isEmpty || store.error != nil {
            VStack(alignment: .leading, spacing: 6) {
                if let error = store.error {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(store.pending) { attachment in
                            HStack(spacing: 6) {
                                Image(systemName: attachment.kind == "image" ? "photo" : "doc")
                                Text(attachment.displayName).lineLimit(1)
                                Button { store.remove(attachment) } label: {
                                    Image(systemName: "xmark.circle.fill")
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Remove \(attachment.displayName)")
                            }
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(Color(.secondarySystemBackground), in: Capsule())
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.top, 6)
        }
    }
}

private struct CommandSuggestions: View {
    @Binding var draft: String
    let commands: [ChatCommand]

    var matches: [ChatCommand] {
        guard draft.hasPrefix("/") else { return [] }
        let query = draft.dropFirst().lowercased()
        return commands.filter { $0.name.lowercased().hasPrefix(query) || $0.aliases.contains { $0.lowercased().hasPrefix(draft.lowercased()) } }.prefix(6).map { $0 }
    }

    var body: some View {
        if !matches.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(matches) { command in
                        Button("/\(command.name)") { draft = "/\(command.name) " }
                            .buttonStyle(.bordered)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 4)
        }
    }
}
