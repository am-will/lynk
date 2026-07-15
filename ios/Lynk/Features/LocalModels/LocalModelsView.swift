import SwiftUI
import UniformTypeIdentifiers

struct LocalModelsView: View {
    @Environment(LocalModelStore.self) private var models
    @Environment(SettingsStore.self) private var settings
    @State private var showingImporter = false

    var body: some View {
        @Bindable var settings = settings
        List {
            Section {
                Text("GGUF uses llama.cpp when its pinned iOS framework is present. LiteRT-LM import remains visible, but inference is unavailable until a genuine iOS runtime exists.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Imported models") {
                if models.models.isEmpty {
                    ContentUnavailableView("No local models", systemImage: "shippingbox", description: Text("Import a .gguf or .litertlm file."))
                }
                ForEach(models.models) { model in
                    Button {
                        settings.selectedLocalModelID = model.id
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(model.displayName).foregroundStyle(.primary)
                                Text("\(model.kind.label) · \(model.sizeBytes.formatted(.byteCount(style: .file)))")
                                    .font(.caption).foregroundStyle(.secondary)
                                if let unavailable = LocalRuntimeAvailability.message(for: model.kind) {
                                    Text(unavailable).font(.caption2).foregroundStyle(.orange)
                                }
                            }
                            Spacer()
                            if settings.selectedLocalModelID == model.id { Image(systemName: "checkmark.circle.fill") }
                        }
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) {
                            if settings.selectedLocalModelID == model.id { settings.selectedLocalModelID = "" }
                            models.delete(model)
                        }
                    }
                }
            }
            if let error = models.error {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
        .accessibilityIdentifier("local-models-view")
        .navigationTitle("Local Models")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Import", systemImage: "square.and.arrow.down") { showingImporter = true }
                    .disabled(models.isImporting)
            }
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.data], allowsMultipleSelection: false) { result in
            switch result {
            case let .success(urls): if let url = urls.first { Task { await models.importFile(url) } }
            case let .failure(error): models.error = error.localizedDescription
            }
        }
    }
}
