import SwiftUI

struct ModelPickerView: View {
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var models: [ChatModelOption] {
        chat.availableModels.filter { search.isEmpty || $0.label.localizedCaseInsensitiveContains(search) || $0.id.localizedCaseInsensitiveContains(search) }
    }

    private var groups: [String] { Array(Set(models.map(\.groupLabel))).sorted() }

    var body: some View {
        NavigationStack {
            List {
                ForEach(groups, id: \.self) { group in
                    Section(group) {
                        ForEach(models.filter { $0.groupLabel == group }) { model in
                            Button {
                                chat.setModel(model.id, deviceID: deviceID, bridge: bridge)
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(model.label).foregroundStyle(.primary)
                                        Text(model.id).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if model.id == chat.selectedModel { Image(systemName: "checkmark") }
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $search, prompt: "Search models")
            .navigationTitle("Models")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

struct SessionPickerView: View {
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient
    let newSession: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(chat.sessions) { session in
                Button {
                    chat.selectSession(session.key, deviceID: deviceID, bridge: bridge)
                    dismiss()
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.title).foregroundStyle(.primary)
                            if let subtitle = session.subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                            HStack {
                                if let harness = session.harnessLabel { Text(harness) }
                                if let workspace = session.workspaceName { Text("• \(workspace)") }
                            }
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                        }
                        Spacer()
                        if let unread = chat.unreadReplies[session.key]?.count, unread > 0 {
                            Text("\(unread)").font(.caption.bold()).foregroundStyle(.white).padding(6).background(.red, in: Circle())
                        }
                        if session.key == chat.sessionKey { Image(systemName: "checkmark") }
                    }
                }
            }
            .overlay {
                if chat.sessions.isEmpty { ContentUnavailableView("No sessions", systemImage: "bubble.left") }
            }
            .navigationTitle("Sessions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button("New", systemImage: "plus", action: newSession) }
            }
        }
    }
}

struct NewSessionView: View {
    let chat: ChatStore
    let deviceID: String
    let bridge: BridgeClient
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var workspacePath = ""
    @State private var createIfMissing = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Chat") {
                    TextField("Name (optional)", text: $label)
                    LabeledContent("Model", value: chat.selectedModelOption?.label ?? chat.selectedModel ?? "Bridge default")
                }
                Section("Workspace") {
                    TextField("Host folder path (optional)", text: $workspacePath)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Toggle("Create folder if missing", isOn: $createIfMissing)
                    Text("The folder is on the host computer. Creation is attempted only when this toggle is enabled.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("New Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        chat.createSession(
                            label: label,
                            workspacePath: workspacePath,
                            createWorkspace: createIfMissing,
                            deviceID: deviceID,
                            bridge: bridge
                        )
                        dismiss()
                    }
                }
            }
        }
    }
}
