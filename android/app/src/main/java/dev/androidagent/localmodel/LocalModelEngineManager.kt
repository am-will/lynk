package dev.androidagent.localmodel

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalModelEnginePhase {
    Open,
    Resetting,
    Closing,
    Closed
}

data class LocalModelEngineSnapshot(
    val phase: LocalModelEnginePhase,
    val activeGeneration: Long?,
    val nextGeneration: Long
)

/**
 * Single ownership boundary for the heavyweight local model runtime.
 *
 * Generations are serialized. Reset and close first reject new generations, then cancel and
 * join the current owner before disposing the native runtime.
 */
class LocalModelEngineManager(
    private val runtimeFactory: () -> LocalModelRuntime
) : LocalModelRuntime {
    constructor(context: Context) : this({ LocalModelRuntimeRouter(context.applicationContext) })

    private val stateMutex = Mutex()
    private val lifecycleTransition = Mutex()
    private val generationPermit = Mutex()
    private var phase = LocalModelEnginePhase.Open
    private var generationSequence = 0L
    private var activeGeneration: ActiveGeneration? = null
    private var runtime: LocalModelRuntime = runtimeFactory()

    override suspend fun generate(
        request: LocalModelRequest,
        onDelta: suspend (String) -> Unit,
        onStatus: suspend (String) -> Unit
    ): String = generationPermit.withLock {
        val owner = stateMutex.withLock {
            check(phase == LocalModelEnginePhase.Open) { "Local model engine is ${phase.name.lowercase()}." }
            ActiveGeneration(
                token = ++generationSequence,
                job = currentCoroutineContext()[Job]
                    ?: error("Local model generation requires a coroutine Job.")
            ).also { activeGeneration = it }
        }
        try {
            runtime.generate(request, onDelta, onStatus)
        } finally {
            stateMutex.withLock {
                if (activeGeneration?.token == owner.token) {
                    activeGeneration = null
                }
            }
        }
    }

    suspend fun cancelActiveAndJoin(reason: String) {
        val active = stateMutex.withLock { activeGeneration }
        active?.job?.cancel(CancellationException(reason))
        active?.job?.join()
    }

    suspend fun resetAndJoin(reason: String) = lifecycleTransition.withLock {
        val active = stateMutex.withLock {
            check(phase == LocalModelEnginePhase.Open) { "Local model engine cannot reset while ${phase.name.lowercase()}." }
            phase = LocalModelEnginePhase.Resetting
            activeGeneration
        }
        active?.job?.cancel(CancellationException(reason))
        active?.job?.join()
        generationPermit.withLock {
            runtime.close()
            runtime = runtimeFactory()
            stateMutex.withLock {
                activeGeneration = null
                phase = LocalModelEnginePhase.Open
            }
        }
    }

    suspend fun closeAndJoin(reason: String = "Local model engine closed") = lifecycleTransition.withLock transition@ {
        val (alreadyClosed, active) = stateMutex.withLock {
            if (phase == LocalModelEnginePhase.Closed) return@withLock true to null
            phase = LocalModelEnginePhase.Closing
            false to activeGeneration
        }
        if (alreadyClosed) return@transition
        active?.job?.cancel(CancellationException(reason))
        active?.job?.join()
        generationPermit.withLock {
            runtime.close()
            stateMutex.withLock {
                activeGeneration = null
                phase = LocalModelEnginePhase.Closed
            }
        }
    }

    suspend fun snapshot(): LocalModelEngineSnapshot = stateMutex.withLock {
        LocalModelEngineSnapshot(phase, activeGeneration?.token, generationSequence + 1)
    }

    /** Compatibility boundary for existing synchronous owners. Prefer [closeAndJoin]. */
    override fun close() {
        runBlocking { closeAndJoin() }
    }

    private data class ActiveGeneration(val token: Long, val job: Job)
}
