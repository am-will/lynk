package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufFailureCategory
import dev.androidagent.localmodel.gguf.GgufModelKey
import dev.androidagent.localmodel.gguf.GgufNativeException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufVulkanFallbackTest {

    @Test
    fun nativeFailureCodesMapToExplicitCategories() {
        GgufFailureCategory.entries.forEach { category ->
            assertEquals(
                category,
                GgufNativeException(category.wireCode, "detail").category
            )
        }
        assertEquals(
            GgufFailureCategory.Configuration,
            GgufNativeException("future_code", "detail").category
        )
    }

    @Test
    fun confirmedVulkanFailureRetriesOnceOnCpuAndDisablesOnlyThisRuntime() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { false }
        val otherRuntime = GgufVulkanFallbackPolicy { false }
        val attempts = mutableListOf<LocalModelBackend>()
        val selections = mutableListOf<GgufCpuSelection>()
        var invalidations = 0

        val result = policy.execute(
            requestedBackend = LocalModelBackend.Gpu,
            onCpuSelected = selections::add,
            beforeCpuRetry = { invalidations++ }
        ) { backend ->
            attempts += backend
            if (backend == LocalModelBackend.Gpu) {
                throw GgufNativeException("vulkan_backend", "VK_ERROR_DEVICE_LOST")
            }
            "cpu result"
        }

        assertEquals("cpu result", result)
        assertEquals(listOf(LocalModelBackend.Gpu, LocalModelBackend.Cpu), attempts)
        assertEquals(listOf(GgufCpuSelection.RuntimeFailure), selections)
        assertEquals(1, invalidations)
        assertTrue(policy.isRuntimeDisabled)
        assertFalse(otherRuntime.isRuntimeDisabled)

        attempts.clear()
        selections.clear()
        assertEquals(
            "later cpu result",
            policy.execute(
                requestedBackend = LocalModelBackend.Gpu,
                onCpuSelected = selections::add
            ) { backend ->
                attempts += backend
                "later cpu result"
            }
        )
        assertEquals(listOf(LocalModelBackend.Cpu), attempts)
        assertEquals(listOf(GgufCpuSelection.RuntimeDisabled), selections)
    }

    @Test
    fun nonVulkanFailuresNeverRetryOrDisableVulkan() {
        val failures = listOf<Throwable>(
            GgufNativeException("model", "missing or invalid model"),
            GgufNativeException("context", "context allocation failed"),
            GgufNativeException("prompt", "prompt is too long"),
            GgufNativeException("cancellation", "cancelled"),
            GgufNativeException("decode", "generic decode failure"),
            GgufNativeException("configuration", "bad configuration"),
            IllegalArgumentException("unsupported image"),
            IllegalStateException("callback failed"),
            CancellationException("stopped")
        )

        failures.forEach { failure ->
            val policy = GgufVulkanFallbackPolicy { false }
            val attempts = mutableListOf<LocalModelBackend>()
            assertThrows(failure::class.java) {
                runBlocking {
                    policy.execute(LocalModelBackend.Gpu) { backend ->
                        attempts += backend
                        throw failure
                    }
                }
            }
            assertEquals(listOf(LocalModelBackend.Gpu), attempts)
            assertFalse(policy.isRuntimeDisabled)
        }
    }

    @Test
    fun explicitVulkanFailureFromCpuAttemptDoesNotRetryOrDisable() {
        val policy = GgufVulkanFallbackPolicy { false }
        val attempts = mutableListOf<LocalModelBackend>()
        val failure = GgufNativeException("vulkan_backend", "backend failure")

        assertThrows(GgufNativeException::class.java) {
            runBlocking {
                policy.execute(LocalModelBackend.Cpu) { backend ->
                    attempts += backend
                    throw failure
                }
            }
        }

        assertEquals(listOf(LocalModelBackend.Cpu), attempts)
        assertFalse(policy.isRuntimeDisabled)
    }

    @Test
    fun devicePolicySelectsCpuWithoutMarkingRuntimeFailure() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { true }
        val selections = mutableListOf<GgufCpuSelection>()
        val attempts = mutableListOf<LocalModelBackend>()

        val result = policy.execute(
            requestedBackend = LocalModelBackend.Gpu,
            onCpuSelected = selections::add
        ) { backend ->
            attempts += backend
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(LocalModelBackend.Cpu), attempts)
        assertEquals(listOf(GgufCpuSelection.DevicePolicy), selections)
        assertFalse(policy.isRuntimeDisabled)
        assertEquals(
            GgufBackendSelection(LocalModelBackend.Cpu, GgufCpuSelection.DevicePolicy),
            policy.selectionFor(LocalModelBackend.Gpu)
        )
    }

    @Test
    fun runtimeFailureChangesEffectiveBackendForLaterProfilesAndOperations() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { false }

        policy.execute(LocalModelBackend.Gpu) { backend ->
            if (backend == LocalModelBackend.Gpu) {
                throw GgufNativeException("vulkan_backend", "device lost")
            }
            Unit
        }

        assertEquals(
            GgufBackendSelection(LocalModelBackend.Cpu, GgufCpuSelection.RuntimeDisabled),
            policy.selectionFor(LocalModelBackend.Gpu)
        )
        assertEquals(
            GgufBackendSelection(LocalModelBackend.Cpu),
            policy.selectionFor(LocalModelBackend.Cpu)
        )
    }

    @Test
    fun profileUsesDeviceSelectedCpuSessionContext() {
        val policy = GgufVulkanFallbackPolicy { true }
        val cache = GgufSessionCache {}
        val config = ggufConfig(LocalModelBackend.Gpu, 65_536)
        val requestedCpuKey = plannedKey(config, LocalModelBackend.Cpu)
        cache.replace(
            requestedCpuKey,
            requestedCpuKey.copy(contextTokens = 32_768),
            41L
        )
        val runtime = GgufRuntime({ TEST_AVAILABLE_BYTES }, cache, policy)

        assertEquals(32_768, runtime.profile(config).effectiveContextTokens)
    }

    @Test
    fun profileUsesRuntimeFallbackCpuSessionContext() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { false }
        policy.execute(LocalModelBackend.Gpu) { backend ->
            if (backend == LocalModelBackend.Gpu) {
                throw GgufNativeException("vulkan_backend", "device lost")
            }
            Unit
        }
        val cache = GgufSessionCache {}
        val config = ggufConfig(LocalModelBackend.Gpu, 65_536)
        val requestedCpuKey = plannedKey(config, LocalModelBackend.Cpu)
        cache.replace(
            requestedCpuKey,
            requestedCpuKey.copy(contextTokens = 16_384),
            42L
        )
        val runtime = GgufRuntime({ TEST_AVAILABLE_BYTES }, cache, policy)

        assertEquals(16_384, runtime.profile(config).effectiveContextTokens)
    }

    @Test
    fun failedGpuDeltasAreDiscardedBeforeCleanCpuCommit() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { false }
        val callerDeltas = mutableListOf<String>()

        val completed = policy.execute(LocalModelBackend.Gpu) { backend ->
            val attempt = GgufAttemptDeltaBuffer()
            if (backend == LocalModelBackend.Gpu) {
                attempt.append("discarded gpu delta")
                throw GgufNativeException("vulkan_backend", "device lost")
            }
            attempt.append("clean ")
            attempt.append("cpu")
            Pair("clean cpu", attempt.snapshot())
        }
        completed.second.forEach(callerDeltas::add)

        assertEquals("clean cpu", completed.first)
        assertEquals(listOf("clean ", "cpu"), callerDeltas)
    }

    @Test
    fun callbackFailureAfterSuccessfulAttemptCannotTriggerRetry() = runBlocking {
        val policy = GgufVulkanFallbackPolicy { false }
        val attempts = mutableListOf<LocalModelBackend>()
        val completed = policy.execute(LocalModelBackend.Gpu) { backend ->
            attempts += backend
            Pair("result", listOf("delta"))
        }

        assertThrows(IllegalStateException::class.java) {
            completed.second.forEach { throw IllegalStateException("callback failed") }
        }
        assertEquals(listOf(LocalModelBackend.Gpu), attempts)
        assertFalse(policy.isRuntimeDisabled)
    }

    private fun ggufConfig(backend: LocalModelBackend, contextTokens: Int): AgentConfig {
        val model = File.createTempFile("profile-test", ".gguf").apply {
            writeText("model")
            deleteOnExit()
        }
        return AgentConfig(
            hostUrl = "",
            deviceId = "test",
            token = "test",
            openAiApiKey = "",
            systemPrompt = "",
            model = "local-litertlm",
            reasoningEffort = "medium",
            localModelPath = model.absolutePath,
            localModelBackend = backend,
            localContextTokens = contextTokens
        )
    }

    private fun plannedKey(config: AgentConfig, backend: LocalModelBackend): GgufModelKey =
        GgufContextPlanner.planKey(
            path = config.localModelPath,
            requestedContext = config.localContextTokens,
            backendKey = backend.key,
            gpuLayers = gpuLayersFor(backend),
            modelBytes = File(config.localModelPath).length(),
            availableBytes = TEST_AVAILABLE_BYTES
        )

    private companion object {
        const val TEST_AVAILABLE_BYTES = 10_000_000_000L
    }
}
