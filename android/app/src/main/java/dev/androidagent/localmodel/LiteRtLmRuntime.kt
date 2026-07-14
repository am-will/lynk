package dev.androidagent.localmodel

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.androidagent.AgentConfig
import dev.androidagent.LocalModelBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class LiteRtLmRuntime(
    private val context: Context
) : LocalModelRuntime {
    private var loadedKey: String? = null
    private var engine: Engine? = null

    override fun profile(config: AgentConfig): LocalModelRuntimeProfile = LocalModelRuntimeProfile(
        kind = LocalModelRuntimeKind.LiteRtLm,
        effectiveContextTokens = config.localContextTokens.coerceAtLeast(LocalModelRuntimeProfile.MIN_CONTEXT_TOKENS),
        supportsImageInput = true
    )

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String =
        withContext(Dispatchers.IO) {
            val activeEngine = engineFor(request.config, visionEnabled = request.imagePaths.isNotEmpty(), onStatus = onStatus)
            onStatus("Local model is generating a response")
            val conversation = activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(request.systemPrompt)
                )
            )
            try {
                val output = StringBuilder()
                val contents = if (request.imagePaths.isEmpty()) {
                    Contents.of(request.prompt)
                } else {
                    Contents.of(request.imagePaths.map { Content.ImageFile(it) } + Content.Text(request.prompt))
                }
                conversation.sendMessageAsync(contents).collect { message ->
                    val delta = message.toString()
                    if (delta.isNotBlank()) {
                        output.append(delta)
                        onDelta(delta)
                    }
                }
                output.toString()
            } finally {
                conversation.close()
            }
        }

    override fun close() {
        engine?.close()
        engine = null
        loadedKey = null
    }

    private suspend fun engineFor(
        config: AgentConfig,
        visionEnabled: Boolean,
        onStatus: suspend (String) -> Unit
    ): Engine {
        val path = config.localModelPath.trim()
        if (!LocalModelStore.exists(path)) {
            throw IllegalStateException("Local LiteRT-LM model is not configured. Import a .litertlm model in Connection & Config.")
        }
        val maxNumTokens = config.localContextTokens.coerceAtLeast(512)
        val key = listOf(path, config.localModelBackend.key, maxNumTokens, "vision=$visionEnabled").joinToString("|")
        engine?.takeIf { loadedKey == key }?.let { return it }
        close()
        onStatus("Loading Local LiteRT-LM model into memory")
        val next = Engine(
            EngineConfig(
                modelPath = path,
                backend = backendFor(config.localModelBackend),
                visionBackend = if (visionEnabled) backendFor(config.localModelBackend) else null,
                maxNumTokens = maxNumTokens,
                maxNumImages = if (visionEnabled) 1 else null,
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            next.initialize()
        } catch (error: Throwable) {
            runCatching { next.close() }
            throw error
        }
        engine = next
        loadedKey = key
        onStatus("Local LiteRT-LM model loaded")
        return next
    }

    private fun backendFor(backend: LocalModelBackend): Backend {
        return when (backend) {
            LocalModelBackend.Cpu -> Backend.CPU()
            LocalModelBackend.Gpu -> Backend.GPU()
            LocalModelBackend.Npu -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        }
    }
}
