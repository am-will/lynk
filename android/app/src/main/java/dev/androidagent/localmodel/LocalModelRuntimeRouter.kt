package dev.androidagent.localmodel

import android.content.Context

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

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String {
        val runtime = synchronized(lock) {
            check(!closed) { "Local model runtime router is closed" }
            when (extensionOf(request.config.localModelPath)) {
                LITERTLM_EXTENSION -> liteRt ?: liteRtFactory().also { liteRt = it }
                GGUF_EXTENSION -> gguf ?: ggufFactory().also { gguf = it }
                else -> throw IllegalArgumentException(
                    "Unsupported local model path: ${request.config.localModelPath}. Only .litertlm and .gguf models are supported."
                )
            }
        }
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

    private fun extensionOf(path: String): String =
        path.trim().substringAfterLast('.', "").lowercase()

    private companion object {
        const val LITERTLM_EXTENSION = "litertlm"
        const val GGUF_EXTENSION = "gguf"
    }
}
