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

    override suspend fun generate(request: LocalModelRequest, onDelta: suspend (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val activeEngine = engineFor(request.config, visionEnabled = request.imagePaths.isNotEmpty())
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

    private fun engineFor(config: AgentConfig, visionEnabled: Boolean): Engine {
        val path = config.localModelPath.trim()
        if (!LocalModelStore.exists(path)) {
            throw IllegalStateException("Local LiteRT-LM model is not configured. Import a .litertlm model in Connection & Config.")
        }
        val maxNumTokens = config.localContextTokens.coerceAtLeast(512)
        val key = listOf(path, config.localModelBackend.key, maxNumTokens, "vision=$visionEnabled").joinToString("|")
        engine?.takeIf { loadedKey == key }?.let { return it }
        close()
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
        next.initialize()
        engine = next
        loadedKey = key
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
