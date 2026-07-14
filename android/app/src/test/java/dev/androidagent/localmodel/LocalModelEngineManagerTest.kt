package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelEngineManagerTest {
    @Test
    fun chatAndVoiceGenerationRequestsAreSerializedWithMonotonicTokens() = runBlocking {
        val runtime = ControlledRuntime()
        val manager = LocalModelEngineManager { runtime }
        val first = async { manager.generate(request("first"), {}, {}) }
        runtime.started.await()
        val firstToken = manager.snapshot().activeGeneration
        val second = async { manager.generate(request("second"), {}, {}) }
        yield()

        assertEquals(1, runtime.maxConcurrent)
        assertFalse(second.isCompleted)
        runtime.release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(1L, firstToken)
        assertEquals(3L, manager.snapshot().nextGeneration)
        manager.closeAndJoin()
    }

    @Test
    fun modelReimportOrRouteResetCancelsAndJoinsBeforeReplacingRuntime() = runBlocking {
        val runtimes = mutableListOf<ControlledRuntime>()
        val manager = LocalModelEngineManager {
            ControlledRuntime().also(runtimes::add)
        }
        val generation = launch { manager.generate(request("first"), {}, {}) }
        runtimes.single().started.await()

        manager.resetAndJoin("model changed")

        assertTrue(generation.isCancelled)
        assertTrue(runtimes[0].closed)
        assertEquals(2, runtimes.size)
        assertEquals(LocalModelEnginePhase.Open, manager.snapshot().phase)
        manager.closeAndJoin()
    }

    @Test
    fun closeRejectsNewWorkAndDisposesAfterGenerationUnwinds() = runBlocking {
        val runtime = ControlledRuntime()
        val manager = LocalModelEngineManager { runtime }
        val generation = launch { manager.generate(request("first"), {}, {}) }
        runtime.started.await()

        manager.closeAndJoin()

        assertTrue(generation.isCancelled)
        assertTrue(runtime.closed)
        assertEquals(LocalModelEnginePhase.Closed, manager.snapshot().phase)
        val rejected = runCatching { manager.generate(request("late"), {}, {}) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun closeDuringIoGenerationWaitsForCancellationCleanupBeforeNativeDispose() = runBlocking {
        val runtime = SlowIoRuntime()
        val manager = LocalModelEngineManager { runtime }
        val generation = launch { manager.generate(request("io"), {}, {}) }
        runtime.started.await()
        val closing = async { manager.closeAndJoin("route switched") }
        runtime.cancelling.await()

        assertFalse(closing.isCompleted)
        assertFalse(runtime.closed)
        runtime.releaseCleanup.complete(Unit)
        closing.await()

        assertTrue(generation.isCancelled)
        assertTrue(runtime.closed)
    }

    private fun request(prompt: String) = LocalModelRequest(prompt, "system", config())

    private fun config() = AgentConfig(
        hostUrl = "ws://127.0.0.1:8788/phone",
        deviceId = "phone",
        token = "token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "local-litertlm",
        reasoningEffort = "medium"
    )

    private class ControlledRuntime : LocalModelRuntime {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        var concurrent = 0
        var maxConcurrent = 0

        override fun profile(config: AgentConfig): LocalModelRuntimeProfile = testProfile(config)

        override suspend fun generate(
            request: LocalModelRequest,
            onDelta: suspend (String) -> Unit,
            onStatus: suspend (String) -> Unit
        ): String {
            concurrent += 1
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            started.complete(Unit)
            return try {
                release.await()
                request.prompt
            } finally {
                concurrent -= 1
            }
        }

        override fun close() {
            check(concurrent == 0) { "runtime closed while generation was active" }
            closed = true
        }
    }

    private class SlowIoRuntime : LocalModelRuntime {
        val started = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var closed = false

        override fun profile(config: AgentConfig): LocalModelRuntimeProfile = testProfile(config)

        override suspend fun generate(
            request: LocalModelRequest,
            onDelta: suspend (String) -> Unit,
            onStatus: suspend (String) -> Unit
        ): String = withContext(Dispatchers.IO) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancelling.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }

        override fun close() {
            check(cancelling.isCompleted && releaseCleanup.isCompleted)
            closed = true
        }
    }

    private companion object {
        fun testProfile(config: AgentConfig) = LocalModelRuntimeProfile(
            kind = LocalModelRuntimeKind.LiteRtLm,
            effectiveContextTokens = config.localContextTokens.coerceAtLeast(512),
            supportsImageInput = true
        )
    }
}
