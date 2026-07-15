import Foundation
import llama

private enum LlamaBackendLifetime {
    static let initialize: Void = {
        llama_backend_init()
    }()
}

public actor LynkLlamaRuntime {
    public enum ExecutionMode: String, Sendable {
        case cpuOnly
        case metalWithCPUFallback
    }

    public struct Availability: Sendable, Equatable {
        public let isAvailable: Bool
        public let executionMode: ExecutionMode
        public let detail: String
    }

    public struct ModelConfiguration: Sendable, Equatable {
        public static let minimumContextSize = 512
        public static let maximumContextSize = 32_768
        public static let maximumBatchSize = 512

        public let contextSize: Int
        public let batchSize: Int
        public let threadCount: Int
        public let gpuLayerCount: Int32

        public init(
            contextSize: Int = 4_096,
            batchSize: Int = 512,
            threadCount: Int = 0,
            gpuLayerCount: Int32 = -1
        ) {
            let boundedContextSize = min(
                max(contextSize, Self.minimumContextSize),
                Self.maximumContextSize
            )
            let automaticThreadCount = max(
                1,
                min(8, ProcessInfo.processInfo.activeProcessorCount - 2)
            )

            self.contextSize = boundedContextSize
            self.batchSize = min(
                max(batchSize, 1),
                min(boundedContextSize, Self.maximumBatchSize)
            )
            self.threadCount = min(max(threadCount > 0 ? threadCount : automaticThreadCount, 1), 8)
            self.gpuLayerCount = min(max(gpuLayerCount, -1), 1_024)
        }
    }

    public struct GenerationConfiguration: Sendable, Equatable {
        public static let maximumTokenCount = 4_096

        public let maximumTokenCount: Int
        public let temperature: Float
        public let topK: Int32
        public let topP: Float
        public let seed: UInt32

        public init(
            maximumTokenCount: Int = 512,
            temperature: Float = 0.7,
            topK: Int32 = 40,
            topP: Float = 0.95,
            seed: UInt32 = .max
        ) {
            self.maximumTokenCount = min(max(maximumTokenCount, 1), Self.maximumTokenCount)
            self.temperature = min(max(temperature, 0), 2)
            self.topK = min(max(topK, 1), 100)
            self.topP = min(max(topP, 0.05), 1)
            self.seed = seed
        }
    }

    public struct Status: Sendable, Equatable {
        public let availability: Availability
        public let isLoaded: Bool
        public let isGenerating: Bool
        public let loadedModelName: String?
        public let contextSize: Int?
    }

    public enum RuntimeError: Error, LocalizedError, Sendable, Equatable {
        case invalidModelURL
        case unsupportedModelFormat
        case modelLoadFailed
        case contextCreationFailed
        case batchAllocationFailed
        case notLoaded
        case busy
        case emptyPrompt
        case promptTooLong(promptTokenCount: Int, contextSize: Int)
        case tokenizationFailed
        case samplerCreationFailed
        case decodeFailed(code: Int32)
        case tokenConversionFailed

        public var errorDescription: String? {
            switch self {
            case .invalidModelURL:
                return "The model URL is not a readable local file."
            case .unsupportedModelFormat:
                return "Lynk local inference requires a .gguf model file."
            case .modelLoadFailed:
                return "llama.cpp could not load the GGUF model."
            case .contextCreationFailed:
                return "llama.cpp could not create an inference context."
            case .batchAllocationFailed:
                return "llama.cpp could not allocate a token batch."
            case .notLoaded:
                return "Load a GGUF model before generating text."
            case .busy:
                return "The local model runtime is already generating text."
            case .emptyPrompt:
                return "The prompt must not be empty."
            case let .promptTooLong(promptTokenCount, contextSize):
                return "The prompt has \(promptTokenCount) tokens but the context holds \(contextSize)."
            case .tokenizationFailed:
                return "llama.cpp could not tokenize the prompt."
            case .samplerCreationFailed:
                return "llama.cpp could not create a token sampler."
            case let .decodeFailed(code):
                return "llama.cpp decoding failed with code \(code)."
            case .tokenConversionFailed:
                return "llama.cpp could not convert a generated token to text."
            }
        }
    }

    public typealias TokenHandler = @Sendable (String) async -> Void

    public nonisolated static var availability: Availability {
#if targetEnvironment(simulator)
        Availability(
            isAvailable: true,
            executionMode: .cpuOnly,
            detail: "Available with CPU-only execution in the iOS Simulator."
        )
#else
        Availability(
            isAvailable: true,
            executionMode: .metalWithCPUFallback,
            detail: "Available with Metal-preferred execution and CPU fallback."
        )
#endif
    }

    public var status: Status {
        Status(
            availability: Self.availability,
            isLoaded: model != nil && context != nil,
            isGenerating: isGenerating,
            loadedModelName: loadedModelName,
            contextSize: actualContextSize
        )
    }

    private var model: OpaquePointer?
    private var context: OpaquePointer?
    private var vocab: OpaquePointer?
    private var batch: llama_batch?
    private var batchCapacity: Int?
    private var loadedModelName: String?
    private var actualContextSize: Int?
    private var isGenerating = false
    private var cancellationRequested = false
    private var unloadAfterGeneration = false

    public init() {}

    deinit {
        if let batch {
            llama_batch_free(batch)
        }
        if let context {
            llama_free(context)
        }
        if let model {
            llama_model_free(model)
        }
    }

    public func load(
        modelAt url: URL,
        configuration: ModelConfiguration = ModelConfiguration()
    ) throws {
        guard !isGenerating else {
            throw RuntimeError.busy
        }
        guard url.isFileURL,
              url.pathExtension.lowercased() == "gguf",
              FileManager.default.isReadableFile(atPath: url.path) else {
            if url.pathExtension.lowercased() != "gguf" {
                throw RuntimeError.unsupportedModelFormat
            }
            throw RuntimeError.invalidModelURL
        }

        releaseLoadedModel()
        _ = LlamaBackendLifetime.initialize

        var modelParameters = llama_model_default_params()
#if targetEnvironment(simulator)
        modelParameters.n_gpu_layers = 0
#else
        modelParameters.n_gpu_layers = configuration.gpuLayerCount
#endif

        let loadedModel = url.path.withCString { path in
            llama_model_load_from_file(path, modelParameters)
        }
        guard let loadedModel else {
            throw RuntimeError.modelLoadFailed
        }

        var contextParameters = llama_context_default_params()
        contextParameters.n_ctx = UInt32(configuration.contextSize)
        contextParameters.n_batch = UInt32(configuration.batchSize)
        contextParameters.n_ubatch = UInt32(configuration.batchSize)
        contextParameters.n_threads = Int32(configuration.threadCount)
        contextParameters.n_threads_batch = Int32(configuration.threadCount)
#if targetEnvironment(simulator)
        contextParameters.offload_kqv = false
        contextParameters.op_offload = false
#endif

        guard let loadedContext = llama_init_from_model(loadedModel, contextParameters) else {
            llama_model_free(loadedModel)
            throw RuntimeError.contextCreationFailed
        }
        guard let loadedVocab = llama_model_get_vocab(loadedModel) else {
            llama_free(loadedContext)
            llama_model_free(loadedModel)
            throw RuntimeError.contextCreationFailed
        }

        let contextSize = Int(llama_n_ctx(loadedContext))
        let batchCapacity = max(1, min(configuration.batchSize, contextSize))
        let loadedBatch = llama_batch_init(Int32(batchCapacity), 0, 1)
        guard loadedBatch.token != nil,
              loadedBatch.pos != nil,
              loadedBatch.n_seq_id != nil,
              loadedBatch.seq_id != nil,
              loadedBatch.logits != nil else {
            llama_batch_free(loadedBatch)
            llama_free(loadedContext)
            llama_model_free(loadedModel)
            throw RuntimeError.batchAllocationFailed
        }

        model = loadedModel
        context = loadedContext
        vocab = loadedVocab
        batch = loadedBatch
        self.batchCapacity = batchCapacity
        loadedModelName = url.lastPathComponent
        actualContextSize = contextSize
        cancellationRequested = false
        unloadAfterGeneration = false
    }

    public func generate(
        prompt: String,
        configuration: GenerationConfiguration = GenerationConfiguration(),
        onToken: TokenHandler? = nil
    ) async throws -> String {
        guard !isGenerating else {
            throw RuntimeError.busy
        }
        guard !prompt.isEmpty else {
            throw RuntimeError.emptyPrompt
        }
        guard let context,
              let vocab,
              var workingBatch = batch,
              let batchCapacity else {
            throw RuntimeError.notLoaded
        }

        let promptTokens = try Self.tokenize(prompt, using: vocab)
        let contextSize = Int(llama_n_ctx(context))
        guard !promptTokens.isEmpty, promptTokens.count < contextSize else {
            throw RuntimeError.promptTooLong(
                promptTokenCount: promptTokens.count,
                contextSize: contextSize
            )
        }

        let generationLimit = min(
            configuration.maximumTokenCount,
            contextSize - promptTokens.count
        )
        guard generationLimit > 0 else {
            throw RuntimeError.promptTooLong(
                promptTokenCount: promptTokens.count,
                contextSize: contextSize
            )
        }

        let sampler = try Self.makeSampler(configuration: configuration)
        defer {
            llama_sampler_free(sampler)
        }

        isGenerating = true
        cancellationRequested = false
        defer {
            finishGeneration()
        }

        llama_memory_clear(llama_get_memory(context), true)

        var chunkStart = 0
        while chunkStart < promptTokens.count {
            try checkCancellation()
            let chunkEnd = min(chunkStart + batchCapacity, promptTokens.count)
            let chunk = promptTokens[chunkStart..<chunkEnd]
            Self.fillBatch(
                &workingBatch,
                tokens: chunk,
                positionOffset: chunkStart,
                markLastTokenForOutput: chunkEnd == promptTokens.count
            )

            let result = llama_decode(context, workingBatch)
            guard result == 0 else {
                throw RuntimeError.decodeFailed(code: result)
            }
            chunkStart = chunkEnd
            await Task.yield()
        }

        var output = ""
        var pendingUTF8: [UInt8] = []

        for generatedIndex in 0..<generationLimit {
            try checkCancellation()

            let token = llama_sampler_sample(sampler, context, workingBatch.n_tokens - 1)
            if llama_vocab_is_eog(vocab, token) {
                break
            }

            pendingUTF8.append(contentsOf: try Self.pieceBytes(for: token, using: vocab))
            if let fragment = String(bytes: pendingUTF8, encoding: .utf8) {
                pendingUTF8.removeAll(keepingCapacity: true)
                output += fragment
                if let onToken, !fragment.isEmpty {
                    await onToken(fragment)
                }
            }

            if generatedIndex + 1 >= generationLimit {
                break
            }

            Self.fillBatch(
                &workingBatch,
                tokens: CollectionOfOne(token),
                positionOffset: promptTokens.count + generatedIndex,
                markLastTokenForOutput: true
            )
            let result = llama_decode(context, workingBatch)
            guard result == 0 else {
                throw RuntimeError.decodeFailed(code: result)
            }
            await Task.yield()
        }

        if !pendingUTF8.isEmpty {
            let fragment = String(decoding: pendingUTF8, as: UTF8.self)
            output += fragment
            if let onToken, !fragment.isEmpty {
                await onToken(fragment)
            }
        }

        return output
    }

    public func cancel() {
        cancellationRequested = isGenerating
    }

    public func unload() {
        if isGenerating {
            cancellationRequested = true
            unloadAfterGeneration = true
            return
        }
        releaseLoadedModel()
    }

    private func checkCancellation() throws {
        if cancellationRequested || Task.isCancelled {
            throw CancellationError()
        }
    }

    private func finishGeneration() {
        isGenerating = false
        cancellationRequested = false
        if unloadAfterGeneration {
            unloadAfterGeneration = false
            releaseLoadedModel()
        }
    }

    private func releaseLoadedModel() {
        if let batch {
            llama_batch_free(batch)
            self.batch = nil
        }
        if let context {
            llama_free(context)
            self.context = nil
        }
        if let model {
            llama_model_free(model)
            self.model = nil
        }
        vocab = nil
        batchCapacity = nil
        loadedModelName = nil
        actualContextSize = nil
    }

    private static func makeSampler(
        configuration: GenerationConfiguration
    ) throws -> UnsafeMutablePointer<llama_sampler> {
        guard let chain = llama_sampler_chain_init(llama_sampler_chain_default_params()) else {
            throw RuntimeError.samplerCreationFailed
        }

        do {
            if configuration.temperature == 0 {
                try addSampler(llama_sampler_init_greedy(), to: chain)
            } else {
                try addSampler(llama_sampler_init_top_k(configuration.topK), to: chain)
                try addSampler(llama_sampler_init_top_p(configuration.topP, 1), to: chain)
                try addSampler(llama_sampler_init_temp(configuration.temperature), to: chain)
                try addSampler(llama_sampler_init_dist(configuration.seed), to: chain)
            }
            return chain
        } catch {
            llama_sampler_free(chain)
            throw error
        }
    }

    private static func addSampler(
        _ sampler: UnsafeMutablePointer<llama_sampler>?,
        to chain: UnsafeMutablePointer<llama_sampler>
    ) throws {
        guard let sampler else {
            throw RuntimeError.samplerCreationFailed
        }
        llama_sampler_chain_add(chain, sampler)
    }

    private static func tokenize(
        _ text: String,
        using vocab: OpaquePointer
    ) throws -> [llama_token] {
        let byteCount = text.utf8.count
        guard byteCount <= Int(Int32.max) else {
            throw RuntimeError.tokenizationFailed
        }

        var capacity = max(8, byteCount + 8)
        while capacity <= Int(Int32.max) {
            var tokens = [llama_token](repeating: 0, count: capacity)
            let tokenCount = tokens.withUnsafeMutableBufferPointer { tokenBuffer in
                text.withCString { textPointer in
                    llama_tokenize(
                        vocab,
                        textPointer,
                        Int32(byteCount),
                        tokenBuffer.baseAddress,
                        Int32(capacity),
                        true,
                        false
                    )
                }
            }

            if tokenCount >= 0 {
                return Array(tokens.prefix(Int(tokenCount)))
            }
            guard tokenCount != Int32.min else {
                throw RuntimeError.tokenizationFailed
            }
            capacity = Int(-tokenCount)
        }

        throw RuntimeError.tokenizationFailed
    }

    private static func pieceBytes(
        for token: llama_token,
        using vocab: OpaquePointer
    ) throws -> [UInt8] {
        var capacity = 32
        while capacity <= Int(Int32.max) {
            var buffer = [CChar](repeating: 0, count: capacity)
            let byteCount = buffer.withUnsafeMutableBufferPointer { byteBuffer in
                llama_token_to_piece(
                    vocab,
                    token,
                    byteBuffer.baseAddress,
                    Int32(capacity),
                    0,
                    false
                )
            }

            if byteCount >= 0 {
                return buffer.prefix(Int(byteCount)).map(UInt8.init(bitPattern:))
            }
            guard byteCount != Int32.min else {
                throw RuntimeError.tokenConversionFailed
            }
            capacity = Int(-byteCount)
        }

        throw RuntimeError.tokenConversionFailed
    }

    private static func fillBatch<T: Collection>(
        _ batch: inout llama_batch,
        tokens: T,
        positionOffset: Int,
        markLastTokenForOutput: Bool
    ) where T.Element == llama_token, T.Index == Int {
        batch.n_tokens = 0
        for (relativeIndex, token) in tokens.enumerated() {
            let index = Int(batch.n_tokens)
            batch.token[index] = token
            batch.pos[index] = llama_pos(positionOffset + relativeIndex)
            batch.n_seq_id[index] = 1
            batch.seq_id[index]![0] = 0
            batch.logits[index] = markLastTokenForOutput && relativeIndex == tokens.count - 1 ? 1 : 0
            batch.n_tokens += 1
        }
    }
}
