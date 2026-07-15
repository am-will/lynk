import Foundation
import LynkLlamaRuntime

actor LlamaRuntime: LocalModelRuntime {
    private let engine = LynkLlamaRuntime()

    static var isAvailable: Bool { LynkLlamaRuntime.availability.isAvailable }

    func load(modelAt url: URL) async throws {
        do {
            try await engine.load(modelAt: url)
        } catch {
            throw LocalModelError.generationFailed(error.localizedDescription)
        }
    }

    func generate(
        prompt: String,
        maximumTokens: Int,
        onToken: @escaping @Sendable (String) async -> Void
    ) async throws {
        do {
            _ = try await engine.generate(
                prompt: prompt,
                configuration: LynkLlamaRuntime.GenerationConfiguration(maximumTokenCount: maximumTokens),
                onToken: onToken
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw LocalModelError.generationFailed(error.localizedDescription)
        }
    }

    func cancel() async { await engine.cancel() }
    func unload() async { await engine.unload() }
}
