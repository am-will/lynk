package dev.androidagent.localmodel

import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufFailureCategory
import dev.androidagent.localmodel.gguf.GgufNativeException
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
}
