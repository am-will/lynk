import Foundation
import XCTest
@testable import Lynk

final class LocalModelFileStoreTests: XCTestCase {
    func testImportsGGUFIntoPrivateStoreAndPersistsManifest() throws {
        let testRoot = FileManager.default.temporaryDirectory.appending(path: "lynk-model-store-\(UUID().uuidString)")
        let source = FileManager.default.temporaryDirectory.appending(path: "tiny-\(UUID().uuidString).gguf")
        try Data("GGUF-test".utf8).write(to: source)
        defer {
            try? FileManager.default.removeItem(at: testRoot)
            try? FileManager.default.removeItem(at: source)
        }

        let store = LocalModelFileStore(root: testRoot)
        let model = try store.importFile(source, existing: [])

        XCTAssertEqual(model.kind, .gguf)
        XCTAssertEqual(model.displayName, source.lastPathComponent)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.modelURL(model).path))
        XCTAssertEqual(try store.loadManifest(), [model])
    }

    func testRejectsUnsupportedAndEmptyModelFiles() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "lynk-model-store-\(UUID().uuidString)")
        let text = FileManager.default.temporaryDirectory.appending(path: "model-\(UUID().uuidString).txt")
        let emptyGGUF = FileManager.default.temporaryDirectory.appending(path: "model-\(UUID().uuidString).gguf")
        try Data("not a model".utf8).write(to: text)
        FileManager.default.createFile(atPath: emptyGGUF.path, contents: nil)
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: text)
            try? FileManager.default.removeItem(at: emptyGGUF)
        }
        let store = LocalModelFileStore(root: root)

        XCTAssertThrowsError(try store.importFile(text, existing: [])) { XCTAssertEqual($0 as? LocalModelError, .unsupportedFile) }
        XCTAssertThrowsError(try store.importFile(emptyGGUF, existing: [])) { XCTAssertEqual($0 as? LocalModelError, .emptyFile) }
    }

    func testLiteRTIsExplicitlyUnavailableOnIOS() {
        let message = LocalRuntimeAvailability.message(for: .litertLM)
        XCTAssertNotNil(message)
        XCTAssertTrue(message?.contains("no compatible iOS LiteRT-LM runtime") == true)
    }

    func testPinnedGGUFRuntimeIsLinked() {
        XCTAssertTrue(LlamaRuntime.isAvailable)
    }
}

@MainActor
final class LocalInferenceControllerTests: XCTestCase {
    func testMockedRuntimeStreamsIntoLocalTimelineWithCapabilityBoundary() async throws {
        let runtime = MockLocalRuntime(tokens: ["Hello", " from iOS"])
        let files = LocalModelFileStore(root: FileManager.default.temporaryDirectory.appending(path: "lynk-model-run-\(UUID().uuidString)"))
        let models = LocalModelStore(files: files)
        let model = ImportedLocalModel(
            id: "test-model", displayName: "Test GGUF", kind: .gguf, sizeBytes: 8,
            fileName: "test.gguf", importedAt: Date()
        )
        models.models = [model]
        let chat = ChatStore()
        let controller = LocalInferenceController(runtimeFactory: { _ in runtime }, unavailableReason: { _ in nil })

        controller.generate(
            text: "Say hello", systemPrompt: "Be useful.", selectedModelID: model.id,
            models: models, chat: chat
        )
        for _ in 0..<100 where controller.isRunning { await Task.yield() }

        XCTAssertFalse(controller.isRunning)
        XCTAssertEqual(chat.timeline.map(\.role), ["user", "assistant"])
        XCTAssertEqual(chat.timeline.last?.text, "Hello from iOS")
        XCTAssertEqual(chat.status, "Completed locally")
        let prompt = await runtime.capturedPrompt
        let didUnload = await runtime.didUnload
        XCTAssertTrue(prompt.contains("answer in chat only"))
        XCTAssertTrue(prompt.contains("do not have a shell"))
        XCTAssertTrue(didUnload)
    }
}

private actor MockLocalRuntime: LocalModelRuntime {
    let tokens: [String]
    private(set) var capturedPrompt = ""
    private(set) var didUnload = false

    init(tokens: [String]) { self.tokens = tokens }

    func load(modelAt url: URL) async throws {}

    func generate(prompt: String, maximumTokens: Int, onToken: @escaping @Sendable (String) async -> Void) async throws {
        capturedPrompt = prompt
        for token in tokens { await onToken(token) }
    }

    func cancel() async {}
    func unload() async { didUnload = true }
}
