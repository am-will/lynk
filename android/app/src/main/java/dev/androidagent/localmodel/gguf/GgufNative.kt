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
    external fun create(
        modelPath: String,
        contextTokens: Int,
        gpuLayers: Int,
        backendKey: String
    ): Long

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
