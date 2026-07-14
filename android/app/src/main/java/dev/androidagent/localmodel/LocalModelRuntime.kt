package dev.androidagent.localmodel

import dev.androidagent.AgentConfig

data class LocalModelRequest(
    val prompt: String,
    val systemPrompt: String,
    val config: AgentConfig,
    val imagePaths: List<String> = emptyList()
)

enum class LocalModelRuntimeKind {
    LiteRtLm,
    Gguf
}

data class LocalModelRuntimeProfile(
    val kind: LocalModelRuntimeKind,
    val effectiveContextTokens: Int,
    val supportsImageInput: Boolean
) {
    init {
        require(effectiveContextTokens >= MIN_CONTEXT_TOKENS) {
            "Local model context must be at least $MIN_CONTEXT_TOKENS tokens"
        }
    }

    companion object {
        const val MIN_CONTEXT_TOKENS = 512
    }

    val imageInputUnsupportedMessage: String?
        get() = if (supportsImageInput) {
            null
        } else when (kind) {
            LocalModelRuntimeKind.Gguf ->
                "The selected GGUF model does not support image attachments. Use a LiteRT-LM model for image input."
            LocalModelRuntimeKind.LiteRtLm ->
                "The selected local model does not support image attachments."
        }

    fun requireImageInputSupport() {
        imageInputUnsupportedMessage?.let { throw IllegalArgumentException(it) }
    }
}

interface LocalModelRuntime {
    /** Cheap capability view for attachment admission and UI metadata. */
    fun profile(config: AgentConfig): LocalModelRuntimeProfile

    /** Resolves any runtime-dependent values before the caller builds a bounded prompt. */
    suspend fun resolveProfile(
        config: AgentConfig,
        onStatus: suspend (String) -> Unit = {}
    ): LocalModelRuntimeProfile = profile(config)

    suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit = {}
    ): String
    fun close()
}
