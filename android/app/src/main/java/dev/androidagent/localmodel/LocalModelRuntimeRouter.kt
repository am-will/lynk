package dev.androidagent.localmodel

import android.content.Context
import dev.androidagent.AgentConfig

class LocalModelRuntimeRouter(
    private val liteRtFactory: () -> LocalModelRuntime,
    private val ggufFactory: () -> LocalModelRuntime
) : LocalModelRuntime {
    constructor(context: Context) : this(
        { LiteRtLmRuntime(context.applicationContext) },
        { GgufRuntime(context.applicationContext) }
    )

    private val lock = Any()
    private var liteRt: LocalModelRuntime? = null
    private var gguf: LocalModelRuntime? = null
    private var closed = false

    override fun profile(config: AgentConfig): LocalModelRuntimeProfile =
        runtimeFor(config).profile(config)

    override suspend fun resolveProfile(
        config: AgentConfig,
        onStatus: suspend (String) -> Unit
    ): LocalModelRuntimeProfile = runtimeFor(config).resolveProfile(config, onStatus)

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String {
        val runtime = runtimeFor(request.config)
        return runtime.generate(request, onDelta, onStatus)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            liteRt?.close()
            gguf?.close()
            liteRt = null
            gguf = null
        }
    }

    private fun runtimeFor(config: AgentConfig): LocalModelRuntime = synchronized(lock) {
        check(!closed) { "Local model runtime router is closed" }
        when (LocalModelStore.requireFormatForPath(config.localModelPath)) {
            LocalModelStore.ModelFormat.LiteRtLm -> liteRt ?: liteRtFactory().also { liteRt = it }
            LocalModelStore.ModelFormat.Gguf -> gguf ?: ggufFactory().also { gguf = it }
        }
    }
}
