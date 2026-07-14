package dev.androidagent.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.androidagent.AgentConfig
import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufModelKey
import dev.androidagent.localmodel.gguf.GgufNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class GgufRuntime(context: Context) : LocalModelRuntime {
    private val context = context.applicationContext
    private val cache = GgufSessionCache { handle -> safeClose(handle) }

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val originalBackend = request.config.localModelBackend
        val vulkanDisabled = GgufVulkanFallbackState.isGpuDisabled || isKnownUnstableVulkanDevice()
        val initialRequest = if (originalBackend == LocalModelBackend.Gpu && vulkanDisabled) {
            onStatus("Vulkan is unstable on this device; using CPU")
            request.copy(config = request.config.copy(localModelBackend = LocalModelBackend.Cpu))
        } else {
            request
        }

        try {
            generateOnce(initialRequest, onDelta, onStatus)
        } catch (e: Throwable) {
            if (originalBackend != LocalModelBackend.Gpu || initialRequest.config.localModelBackend != LocalModelBackend.Gpu) throw e
            if (GgufVulkanFallbackState.isGpuDisabled) throw e
            if (isCancellation(e)) throw e
            onStatus("GPU generation failed; switching to CPU")
            GgufVulkanFallbackState.isGpuDisabled = true
            try { cache.invalidate() } catch (_: Throwable) {}
            val cpuRequest = request.copy(config = request.config.copy(localModelBackend = LocalModelBackend.Cpu))
            generateOnce(cpuRequest, onDelta, onStatus)
        }
    }

    override fun close() {
        cache.close()
    }

    private fun safeClose(handle: Long) {
        if (handle != 0L) {
            try { GgufNative.close(handle) } catch (_: Throwable) {}
        }
    }

    private fun isCancellation(e: Throwable): Boolean =
        e is CancellationException || e.cause is CancellationException

    private fun isKnownUnstableVulkanDevice(): Boolean =
        Build.SOC_MODEL.equals("SM8850", ignoreCase = true)

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun CoroutineScope.generateOnce(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String {
        validateRequest(request)

        val config = request.config
        val path = config.localModelPath.trim()
        require(File(path).isFile) { "GGUF model not found: $path" }

        val backend = config.localModelBackend
        val requestedContext = config.localContextTokens.coerceAtLeast(512)
        val modelBytes = File(path).length()
        val availableBytes = availableMemoryBytes()
        val gpuLayers = gpuLayersFor(backend)
        val backendKey = if (backend == LocalModelBackend.Npu) LocalModelBackend.Cpu.key else backend.key

        val requestedKey = GgufContextPlanner.planKey(
            path = path,
            requestedContext = requestedContext,
            backendKey = backendKey,
            gpuLayers = gpuLayers,
            modelBytes = modelBytes,
            availableBytes = availableBytes
        )

        val handle = sessionFor(requestedKey, backend == LocalModelBackend.Npu, onStatus)

        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) {
                GgufNative.cancel(handle)
            }
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
    ): Long {
        cache.get(requestedKey).let { if (it != 0L) return it }

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

            val handle = try {
                GgufNative.create(
                    candidateKey.path,
                    candidateKey.contextTokens,
                    candidateKey.gpuLayers,
                    candidateKey.backendKey
                )
            } catch (e: Throwable) {
                lastError = e
                0L
            }

            if (handle != 0L) {
                cache.replace(requestedKey, candidateKey, handle)
                val contextSize = GgufNative.getContextSize(handle)
                val backendName = GgufNative.getBackendName(handle)
                onStatus("GGUF model loaded ($contextSize tokens, $backendName)")
                return handle
            }

            onStatus("GGUF context ${candidateKey.contextTokens} failed; retrying")
        }

        throw lastError ?: IllegalStateException("Failed to load GGUF model")
    }

    private fun validateRequest(request: LocalModelRequest) {
        if (request.imagePaths.isNotEmpty()) {
            throw IllegalArgumentException("GGUF runtime does not support image inputs")
        }
    }

    private fun maxOutputTokens(request: LocalModelRequest): Int =
        if (request.systemPrompt.contains("Tools are not needed for this message")) 256 else 128

    private fun availableMemoryBytes(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val reserve = maxOf(memoryInfo.threshold, 512L * 1024 * 1024)
        return (memoryInfo.availMem - reserve).coerceAtLeast(0L)
    }

    private companion object {
        private object END_SENTINEL
    }
}

internal class GgufSessionCache(private val closeHandle: (Long) -> Unit) {
    private val lock = ReentrantLock()
    private var requestedKey: GgufModelKey? = null
    private var effectiveKey: GgufModelKey? = null
    private var handle: Long = 0L
    private var closed = false

    fun get(requested: GgufModelKey): Long = locked {
        check(!closed) { "GGUF runtime is closed" }
        if (handle != 0L && requestedKey == requested) handle else 0L
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

internal object GgufVulkanFallbackState {
    @Volatile
    var isGpuDisabled: Boolean = false
}
