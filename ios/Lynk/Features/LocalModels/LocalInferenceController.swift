import Foundation
import Observation

@MainActor
@Observable
final class LocalInferenceController {
    var isRunning = false
    var error: String?

    private let runtimeFactory: @MainActor (LocalModelKind) -> any LocalModelRuntime
    private let unavailableReason: @MainActor (LocalModelKind) -> String?
    private var runtime: (any LocalModelRuntime)?
    private var task: Task<Void, Never>?

    init(
        runtimeFactory: @escaping @MainActor (LocalModelKind) -> any LocalModelRuntime = { kind in
            kind == .gguf ? LlamaRuntime() : UnavailableLiteRTRuntime()
        },
        unavailableReason: @escaping @MainActor (LocalModelKind) -> String? = LocalRuntimeAvailability.message
    ) {
        self.runtimeFactory = runtimeFactory
        self.unavailableReason = unavailableReason
    }

    func generate(
        text: String,
        systemPrompt: String,
        selectedModelID: String?,
        models: LocalModelStore,
        chat: ChatStore
    ) {
        guard !isRunning else { return }
        guard let selectedModelID, let model = models.models.first(where: { $0.id == selectedModelID }) else {
            error = LocalModelError.noModelSelected.localizedDescription
            chat.failLocalTurn(error ?? "No local model selected.")
            return
        }
        if let unavailable = unavailableReason(model.kind) {
            error = unavailable
            chat.failLocalTurn(unavailable)
            return
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let runID = chat.beginLocalTurn(text: trimmed, model: model.displayName)
        let runtime = runtimeFactory(model.kind)
        self.runtime = runtime
        isRunning = true
        error = nil
        let modelURL = models.url(for: model)
        let prompt = """
        \(systemPrompt)

        Local iOS capability boundary: answer in chat only. You do not have a shell, unrestricted filesystem, screenshots, Android APIs, app control, or phone-control tools.

        User: \(trimmed)
        Assistant:
        """
        task = Task {
            do {
                try await runtime.load(modelAt: modelURL)
                try await runtime.generate(prompt: prompt, maximumTokens: 1_024) { token in
                    await MainActor.run { chat.appendLocalToken(token, runID: runID) }
                }
                await runtime.unload()
                guard !Task.isCancelled else { return }
                chat.finishLocalTurn(runID: runID)
            } catch {
                await runtime.unload()
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
                chat.failLocalTurn(error.localizedDescription, runID: runID)
            }
            self.isRunning = false
            self.runtime = nil
            self.task = nil
        }
    }

    func stop(chat: ChatStore) {
        task?.cancel()
        task = nil
        let runtime = runtime
        Task { await runtime?.cancel(); await runtime?.unload() }
        self.runtime = nil
        isRunning = false
        chat.stopLocalTurn()
    }
}
