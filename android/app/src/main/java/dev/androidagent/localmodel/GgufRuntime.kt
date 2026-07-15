package dev.androidagent.localmodel

import android.app.ActivityManager
import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufCreateOperation
import dev.androidagent.localmodel.gguf.GgufFailureCategory
import dev.androidagent.localmodel.gguf.GgufModelKey
import dev.androidagent.localmodel.gguf.GgufNative
import dev.androidagent.localmodel.gguf.GgufNativeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class GgufRuntime internal constructor(
    private val availableMemoryBytes: () -> Long,
    private val cache: GgufSessionCache,
    private val vulkanFallback: GgufVulkanFallbackPolicy
) : LocalModelRuntime {
    constructor(context: Context) : this(
        availableMemoryBytes = { readAvailableMemoryBytes(context.applicationContext) },
        cache = GgufSessionCache(::safeCloseGgufHandle),
        vulkanFallback = GgufVulkanFallbackPolicy()
    )

    override fun profile(config: AgentConfig): LocalModelRuntimeProfile {
        val effectiveBackend = vulkanFallback.selectionFor(config.localModelBackend).backend
        val effectiveConfig = config.copy(localModelBackend = effectiveBackend)
        val requestedKey = plannedKey(effectiveConfig) ?: return ggufProfile(config.localContextTokens)
        return cache.get(requestedKey)?.let { ggufProfile(it.key.contextTokens) }
            ?: ggufProfile(requestedKey.contextTokens)
    }

    override suspend fun resolveProfile(
        config: AgentConfig,
        onStatus: suspend (String) -> Unit
    ): LocalModelRuntimeProfile = withContext(Dispatchers.IO) {
        withSelectedBackend(config, onStatus) { selectedConfig ->
            val requestedKey = requirePlannedKey(selectedConfig)
            val session = sessionFor(
                requestedKey = requestedKey,
                wasNpu = selectedConfig.localModelBackend == LocalModelBackend.Npu,
                onStatus = onStatus
            )
            ggufProfile(session.key.contextTokens)
        }
    }

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val completed = withSelectedBackend(request.config, onStatus) { selectedConfig ->
            val deltas = GgufAttemptDeltaBuffer()
            val output = generateOnce(request.copy(config = selectedConfig), deltas::append, onStatus)
            GgufCompletedAttempt(output, deltas.snapshot())
        }
        commitGgufDeltas(completed.deltas, onDelta)
        completed.output
    }

    override fun close() {
        cache.close()
    }

    private suspend fun <T> withSelectedBackend(
        config: AgentConfig,
        onStatus: suspend (String) -> Unit,
        operation: suspend (AgentConfig) -> T
    ): T {
        return vulkanFallback.execute(
            requestedBackend = config.localModelBackend,
            onCpuSelected = { reason ->
                when (reason) {
                    GgufCpuSelection.DevicePolicy ->
                        onStatus("Vulkan is disabled for this device; using CPU")
                    GgufCpuSelection.RuntimeDisabled ->
                        onStatus("Vulkan failed earlier in this runtime; using CPU")
                    GgufCpuSelection.RuntimeFailure ->
                        onStatus("Vulkan backend failed; retrying on CPU")
                }
            },
            beforeCpuRetry = {
                try { cache.invalidate() } catch (_: Throwable) {}
            },
            operation = { backend ->
                operation(config.copy(localModelBackend = backend))
            }
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun CoroutineScope.generateOnce(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String {
        validateRequest(request)

        val config = request.config
        val requestedKey = requirePlannedKey(config)

        val session = sessionFor(
            requestedKey,
            config.localModelBackend == LocalModelBackend.Npu,
            onStatus
        )
        val handle = session.handle

        GgufNative.prepareGeneration(handle)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.signalNativeOnCancellation {
            GgufNative.cancel(handle)
        }

        try {
            val queue = LinkedBlockingQueue<Any>()
            val resultRef = AtomicReference<String>()
            val errorRef = AtomicReference<Throwable>()

            val producer = launch {
                try {
                    val output = GgufNative.generate(
                        handle = handle,
                        systemPrompt = request.systemPrompt,
                        userPrompt = request.prompt,
                        maxOutputTokens = maxOutputTokens(request),
                        temperature = 0.7f,
                        topP = 0.95f,
                        topK = 20,
                        callback = object : GgufNative.Callback {
                            override fun onDelta(delta: String) {
                                queue.put(delta)
                            }

                            override fun isCancelled(): Boolean = !isActive
                        }
                    )
                    resultRef.set(output)
                } catch (e: Throwable) {
                    errorRef.set(e)
                } finally {
                    queue.put(END_SENTINEL)
                }
            }

            while (true) {
                ensureActive()
                val item = queue.poll(50, TimeUnit.MILLISECONDS)
                if (item != null) {
                    if (item === END_SENTINEL) break
                    onDelta(item as String)
                }
            }

            producer.join()
            errorRef.get()?.let { throw it }
            return resultRef.get() ?: ""
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun sessionFor(
        requestedKey: GgufModelKey,
        wasNpu: Boolean,
        onStatus: suspend (String) -> Unit
    ): GgufSession {
        cache.get(requestedKey)?.let { return it }

        onStatus("Loading GGUF model")
        if (wasNpu) {
            onStatus("GGUF NPU backend unavailable; using CPU")
        }

        GgufNative.ensureLoaded()

        val candidates = GgufContextPlanner.candidateContexts(requestedKey.contextTokens)
        var lastError: Throwable? = null

        for (contextTokens in candidates) {
            currentCoroutineContext().ensureActive()
            val candidateKey = requestedKey.copy(contextTokens = contextTokens)

            cache.replace(requestedKey, candidateKey, 0L)

            val loadedSession = try {
                createAndCacheSession(requestedKey, candidateKey)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!e.allowsContextDownshift()) throw e
                lastError = e
                null
            }

            if (loadedSession != null) {
                onStatus(
                    "GGUF model loaded (${loadedSession.session.key.contextTokens} tokens, " +
                        "${loadedSession.backendName})"
                )
                return loadedSession.session
            }

            onStatus("GGUF context ${candidateKey.contextTokens} failed; retrying")
        }

        throw lastError ?: IllegalStateException("Failed to load GGUF model")
    }

    private suspend fun createAndCacheSession(
        requestedKey: GgufModelKey,
        candidateKey: GgufModelKey
    ): LoadedGgufSession {
        val operation = GgufCreateOperation()
        val pendingHandle = GgufPendingHandle(::safeCloseGgufHandle)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.signalNativeOnCancellation {
            operation.cancel()
            pendingHandle.cancel()
        }
        try {
            currentCoroutineContext().ensureActive()
            pendingHandle.attach(operation.create(candidateKey))
            currentCoroutineContext().ensureActive()
            val handle = pendingHandle.handleOrThrow()
            val contextSize = GgufNative.getContextSize(handle)
            val backendName = GgufNative.getBackendName(handle)
            val effectiveKey = candidateKey.copy(contextTokens = contextSize)
            currentCoroutineContext().ensureActive()
            check(pendingHandle.transfer { ownedHandle ->
                cache.replace(requestedKey, effectiveKey, ownedHandle)
            }) { "GGUF create operation was cancelled before cache handoff" }
            return LoadedGgufSession(GgufSession(handle, effectiveKey), backendName)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancelHandle?.dispose()
            operation.close()
            pendingHandle.discard()
        }
    }

    private fun validateRequest(request: LocalModelRequest) {
        if (request.imagePaths.isNotEmpty()) {
            throw IllegalArgumentException("GGUF runtime does not support image inputs")
        }
    }

    private fun plannedKey(config: AgentConfig): GgufModelKey? {
        val path = config.localModelPath.trim()
        val model = File(path)
        if (!model.isFile) return null
        val backend = config.localModelBackend
        return GgufContextPlanner.planKey(
            path = path,
            requestedContext = config.localContextTokens.coerceAtLeast(LocalModelRuntimeProfile.MIN_CONTEXT_TOKENS),
            backendKey = if (backend == LocalModelBackend.Npu) LocalModelBackend.Cpu.key else backend.key,
            gpuLayers = gpuLayersFor(backend),
            modelBytes = model.length(),
            availableBytes = availableMemoryBytes()
        )
    }

    private fun requirePlannedKey(config: AgentConfig): GgufModelKey =
        plannedKey(config) ?: throw IllegalArgumentException(
            "GGUF model not found: ${config.localModelPath.trim()}"
        )

    private fun ggufProfile(contextTokens: Int): LocalModelRuntimeProfile = LocalModelRuntimeProfile(
        kind = LocalModelRuntimeKind.Gguf,
        effectiveContextTokens = contextTokens.coerceAtLeast(LocalModelRuntimeProfile.MIN_CONTEXT_TOKENS),
        supportsImageInput = false
    )

    private fun maxOutputTokens(request: LocalModelRequest): Int =
        if (request.systemPrompt.contains("Tools are not needed for this message")) 256 else 128

    private companion object {
        private object END_SENTINEL
    }
}

private fun safeCloseGgufHandle(handle: Long) {
    if (handle != 0L) {
        try { GgufNative.close(handle) } catch (_: Throwable) {}
    }
}

private fun readAvailableMemoryBytes(context: Context): Long {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val reserve = maxOf(memoryInfo.threshold, 512L * 1024 * 1024)
    return (memoryInfo.availMem - reserve).coerceAtLeast(0L)
}

@OptIn(InternalCoroutinesApi::class)
internal fun Job.signalNativeOnCancellation(cancelNative: () -> Unit): DisposableHandle {
    val signalled = AtomicBoolean(false)
    return invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null && signalled.compareAndSet(false, true)) {
            try {
                cancelNative()
            } catch (_: Throwable) {
                // Native cancellation is best-effort and must not disrupt Job cancellation.
            }
        }
    }
}

internal data class GgufSession(val handle: Long, val key: GgufModelKey)

private data class LoadedGgufSession(val session: GgufSession, val backendName: String)

/** Serializes cancellation against the one-time handoff of a new handle to the cache. */
internal class GgufPendingHandle(private val closeHandle: (Long) -> Unit) {
    private val lock = Any()
    private var handle = 0L
    private var cancelled = false
    private var transferred = false

    fun attach(newHandle: Long) {
        var closeImmediately = false
        synchronized(lock) {
            check(handle == 0L && !transferred) { "GGUF handle is already attached" }
            if (cancelled) {
                closeImmediately = true
            } else {
                handle = newHandle
            }
        }
        if (closeImmediately && newHandle != 0L) closeHandle(newHandle)
    }

    fun handleOrThrow(): Long = synchronized(lock) {
        check(!cancelled && handle != 0L) { "GGUF handle is unavailable" }
        handle
    }

    fun transfer(accept: (Long) -> Unit): Boolean = synchronized(lock) {
        if (cancelled || handle == 0L) return@synchronized false
        val ownedHandle = handle
        handle = 0L
        transferred = true
        accept(ownedHandle)
        true
    }

    fun cancel() {
        val ownedHandle = synchronized(lock) {
            cancelled = true
            takeHandle()
        }
        if (ownedHandle != 0L) closeHandle(ownedHandle)
    }

    fun discard() {
        val ownedHandle = synchronized(lock) { takeHandle() }
        if (ownedHandle != 0L) closeHandle(ownedHandle)
    }

    private fun takeHandle(): Long {
        val ownedHandle = handle
        handle = 0L
        return ownedHandle
    }
}

internal class GgufSessionCache(private val closeHandle: (Long) -> Unit) {
    private val lock = ReentrantLock()
    private var requestedKey: GgufModelKey? = null
    private var effectiveKey: GgufModelKey? = null
    private var handle: Long = 0L
    private var closed = false

    fun get(requested: GgufModelKey): GgufSession? = locked {
        check(!closed) { "GGUF runtime is closed" }
        if (handle != 0L && requestedKey == requested) {
            GgufSession(handle, checkNotNull(effectiveKey))
        } else {
            null
        }
    }

    fun replace(requested: GgufModelKey, effective: GgufModelKey, newHandle: Long) = locked {
        if (closed) {
            if (newHandle != 0L) closeHandle(newHandle)
            throw IllegalStateException("GGUF runtime is closed")
        }
        if (handle != 0L && handle != newHandle) {
            closeHandle(handle)
        }
        requestedKey = requested
        effectiveKey = effective
        handle = newHandle
    }

    fun invalidate() = locked {
        if (closed) return@locked
        val h = handle
        requestedKey = null
        effectiveKey = null
        handle = 0L
        if (h != 0L) closeHandle(h)
    }

    fun close() = locked {
        if (closed) return@locked
        closed = true
        val h = handle
        requestedKey = null
        effectiveKey = null
        handle = 0L
        if (h != 0L) closeHandle(h)
    }

    private inline fun <T> locked(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}

internal object GgufContextPlanner {
    private const val RUNTIME_BYTES = 1_300_000_000L
    private const val BYTES_PER_TOKEN = 18L * 1024
    private const val MIN_CONTEXT = 512
    private const val MAX_CONTEXT = 262_144

    val PRESETS = listOf(512, 4096, 8192, 16384, 32768, 65536, 131072, 262144)

    fun planKey(
        path: String,
        requestedContext: Int,
        backendKey: String,
        gpuLayers: Int,
        modelBytes: Long,
        availableBytes: Long
    ): GgufModelKey {
        val context = plan(requestedContext, modelBytes, availableBytes)
        return GgufModelKey(path, context, backendKey, gpuLayers)
    }

    fun plan(requestedContext: Int, modelBytes: Long, availableBytes: Long): Int {
        val target = requestedContext.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
        val usable = availableBytes.coerceAtLeast(0L)
        var selected = MIN_CONTEXT
        for (preset in PRESETS) {
            if (preset > target) break
            if (fits(preset, modelBytes, usable)) {
                selected = preset
            } else {
                break
            }
        }
        return selected
    }

    fun candidateContexts(plannedContext: Int): List<Int> {
        return PRESETS.filter { it <= plannedContext }.reversed()
    }

    private fun fits(context: Int, modelBytes: Long, availableBytes: Long): Boolean {
        return modelBytes + RUNTIME_BYTES + context * BYTES_PER_TOKEN <= availableBytes
    }
}

internal fun gpuLayersFor(backend: LocalModelBackend): Int = when (backend) {
    LocalModelBackend.Gpu -> 999
    LocalModelBackend.Npu -> 0
    LocalModelBackend.Cpu -> 0
}

private fun Throwable.allowsContextDownshift(): Boolean =
    this is GgufNativeException && category == GgufFailureCategory.Context

internal enum class GgufCpuSelection {
    DevicePolicy,
    RuntimeDisabled,
    RuntimeFailure
}

internal data class GgufBackendSelection(
    val backend: LocalModelBackend,
    val cpuReason: GgufCpuSelection? = null
)

/** Vulkan health is scoped to one runtime rather than contaminating the process. */
internal class GgufVulkanFallbackPolicy(
    private val deviceDisablesVulkan: () -> Boolean = GgufDevicePolicy::shouldDisableVulkan
) {
    private val runtimeDisabled = AtomicBoolean(false)

    val isRuntimeDisabled: Boolean
        get() = runtimeDisabled.get()

    fun selectionFor(requestedBackend: LocalModelBackend): GgufBackendSelection {
        if (requestedBackend != LocalModelBackend.Gpu) {
            return GgufBackendSelection(requestedBackend)
        }
        if (deviceDisablesVulkan()) {
            return GgufBackendSelection(LocalModelBackend.Cpu, GgufCpuSelection.DevicePolicy)
        }
        if (runtimeDisabled.get()) {
            return GgufBackendSelection(LocalModelBackend.Cpu, GgufCpuSelection.RuntimeDisabled)
        }
        return GgufBackendSelection(LocalModelBackend.Gpu)
    }

    suspend fun <T> execute(
        requestedBackend: LocalModelBackend,
        onCpuSelected: suspend (GgufCpuSelection) -> Unit = {},
        beforeCpuRetry: suspend () -> Unit = {},
        operation: suspend (LocalModelBackend) -> T
    ): T {
        val selection = selectionFor(requestedBackend)
        selection.cpuReason?.let { onCpuSelected(it) }
        val initialBackend = selection.backend

        try {
            return operation(initialBackend)
        } catch (error: Throwable) {
            if (!canRetryOnCpu(requestedBackend, initialBackend, error)) throw error
            runtimeDisabled.set(true)
            beforeCpuRetry()
            onCpuSelected(GgufCpuSelection.RuntimeFailure)
            return operation(LocalModelBackend.Cpu)
        }
    }

    private fun canRetryOnCpu(
        requestedBackend: LocalModelBackend,
        attemptedBackend: LocalModelBackend,
        error: Throwable
    ): Boolean = requestedBackend == LocalModelBackend.Gpu &&
        attemptedBackend == LocalModelBackend.Gpu &&
        error is GgufNativeException &&
        error.category == GgufFailureCategory.VulkanBackend
}

internal class GgufAttemptDeltaBuffer {
    private val deltas = mutableListOf<String>()

    fun append(delta: String) {
        deltas += delta
    }

    fun snapshot(): List<String> = deltas.toList()
}

internal suspend fun commitGgufDeltas(
    deltas: List<String>,
    onDelta: suspend (String) -> Unit
) {
    for (delta in deltas) {
        currentCoroutineContext().ensureActive()
        onDelta(delta)
    }
}

private data class GgufCompletedAttempt(val output: String, val deltas: List<String>)
