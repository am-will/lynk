import Foundation

protocol LocalModelRuntime: Sendable {
    func load(modelAt url: URL) async throws
    func generate(prompt: String, maximumTokens: Int, onToken: @escaping @Sendable (String) async -> Void) async throws
    func cancel() async
    func unload() async
}

struct UnavailableLiteRTRuntime: LocalModelRuntime {
    func load(modelAt url: URL) async throws { throw LocalModelError.liteRTUnavailable }
    func generate(prompt: String, maximumTokens: Int, onToken: @escaping @Sendable (String) async -> Void) async throws {
        throw LocalModelError.liteRTUnavailable
    }
    func cancel() async {}
    func unload() async {}
}
