package dev.androidagent.localmodel.gguf

import java.util.concurrent.atomic.AtomicBoolean

internal interface GgufCreateOperations {
    fun begin(): Long
    fun create(
        operationHandle: Long,
        modelPath: String,
        contextTokens: Int,
        gpuLayers: Int,
        backendKey: String
    ): Long
    fun cancel(operationHandle: Long)
    fun close(operationHandle: Long)
}

private object JniGgufCreateOperations : GgufCreateOperations {
    override fun begin(): Long = GgufNative.beginCreateOperation()

    override fun create(
        operationHandle: Long,
        modelPath: String,
        contextTokens: Int,
        gpuLayers: Int,
        backendKey: String
    ): Long = GgufNative.createWithOperation(
        operationHandle,
        modelPath,
        contextTokens,
        gpuLayers,
        backendKey
    )

    override fun cancel(operationHandle: Long) {
        GgufNative.cancelCreateOperation(operationHandle)
    }

    override fun close(operationHandle: Long) {
        GgufNative.closeCreateOperation(operationHandle)
    }
}

/** Owns the cancellation token for one blocking native model/context creation. */
internal class GgufCreateOperation(
    private val native: GgufCreateOperations = JniGgufCreateOperations
) : AutoCloseable {
    private val operationHandle = native.begin()
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun create(key: GgufModelKey): Long {
        check(!closed.get()) { "GGUF create operation is closed" }
        return native.create(
            operationHandle,
            key.path,
            key.contextTokens,
            key.gpuLayers,
            key.backendKey
        )
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            native.cancel(operationHandle)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            native.close(operationHandle)
        }
    }
}
