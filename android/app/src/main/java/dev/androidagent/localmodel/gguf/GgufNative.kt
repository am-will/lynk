package dev.androidagent.localmodel.gguf

import java.util.concurrent.atomic.AtomicBoolean

object GgufNative {
    private val loaded = AtomicBoolean(false)

    @JvmStatic
    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("gguf-runtime")
            } catch (e: UnsatisfiedLinkError) {
                loaded.set(false)
                throw IllegalStateException(
                    "Failed to load gguf-runtime native library. " +
                    "GGUF with Vulkan requires Android 9 (API 28) or later and a Vulkan-capable device.",
                    e
                )
            }
        }
    }

    @JvmStatic
    external fun beginCreateOperation(): Long

    @JvmStatic
    external fun createWithOperation(
        operationHandle: Long,
        modelPath: String,
        contextTokens: Int,
        gpuLayers: Int,
        backendKey: String
    ): Long

    @JvmStatic
    external fun cancelCreateOperation(operationHandle: Long)

    @JvmStatic
    external fun closeCreateOperation(operationHandle: Long)

    /**
     * Convenience entry point for diagnostics that do not have a coroutine lifecycle.
     * Production loading uses [GgufCreateOperation] so cancellation can reach native
     * code before a session handle exists.
     */
    @JvmStatic
    fun create(
        modelPath: String,
        contextTokens: Int,
        gpuLayers: Int,
        backendKey: String
    ): Long {
        val operationHandle = beginCreateOperation()
        return try {
            createWithOperation(
                operationHandle,
                modelPath,
                contextTokens,
                gpuLayers,
                backendKey
            )
        } finally {
            closeCreateOperation(operationHandle)
        }
    }

    @JvmStatic
    external fun generate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        callback: Callback
    ): String

    @JvmStatic
    external fun prepareGeneration(handle: Long)

    @JvmStatic
    external fun close(handle: Long)

    @JvmStatic
    external fun cancel(handle: Long)

    @JvmStatic
    external fun getContextSize(handle: Long): Int

    @JvmStatic
    external fun getBackendName(handle: Long): String

    interface Callback {
        fun onDelta(delta: String)
        fun isCancelled(): Boolean
    }
}
