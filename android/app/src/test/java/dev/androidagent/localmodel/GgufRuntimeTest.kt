package dev.androidagent.localmodel

import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufModelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class GgufRuntimeTest {

    @Before
    fun resetFallbackState() {
        GgufVulkanFallbackState.isGpuDisabled = false
    }

    @Test
    fun gpuLayersForGpuRequestsOffload() {
        assertEquals(999, gpuLayersFor(LocalModelBackend.Gpu))
    }

    @Test
    fun gpuLayersForNpuFallsBackToCpu() {
        assertEquals(0, gpuLayersFor(LocalModelBackend.Npu))
    }

    @Test
    fun gpuLayersForCpuRequestsNoOffload() {
        assertEquals(0, gpuLayersFor(LocalModelBackend.Cpu))
    }

    @Test
    fun modelKeyReflectsPathContextBackendAndGpuLayers() {
        val key = GgufModelKey("/models/model.gguf", 4096, "gpu", 999)
        assertEquals("/models/model.gguf", key.path)
        assertEquals(4096, key.contextTokens)
        assertEquals("gpu", key.backendKey)
        assertEquals(999, key.gpuLayers)
    }

    @Test
    fun modelKeyEqualityDependsOnAllFields() {
        val a = GgufModelKey("/models/a.gguf", 2048, "cpu", 0)
        val b = GgufModelKey("/models/a.gguf", 2048, "cpu", 0)
        val c = GgufModelKey("/models/a.gguf", 4096, "cpu", 0)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun plannerSelectsLargestFittingPreset() {
        val modelBytes = 1_000_000_000L
        val available = 3_000_000_000L
        assertEquals(32768, GgufContextPlanner.plan(262144, modelBytes, available))
        assertEquals(4096, GgufContextPlanner.plan(4096, modelBytes, available))
    }

    @Test
    fun plannerFallsBackWhenMemoryTight() {
        val modelBytes = 1_000_000_000L
        val available = 1_500_000_000L
        assertEquals(512, GgufContextPlanner.plan(4096, modelBytes, available))
    }

    @Test
    fun plannerClampsRequestedContext() {
        assertEquals(512, GgufContextPlanner.plan(100, 0L, 10_000_000_000L))
        assertEquals(262144, GgufContextPlanner.plan(1_000_000, 0L, 10_000_000_000L))
    }

    @Test
    fun candidateContextsAreDescendingFromPlanned() {
        assertEquals(listOf(8192, 4096, 512), GgufContextPlanner.candidateContexts(8192))
        assertEquals(listOf(512), GgufContextPlanner.candidateContexts(512))
    }

    @Test
    fun planKeyUsesPlannedContext() {
        val key = GgufContextPlanner.planKey(
            "/models/model.gguf",
            32768,
            "gpu",
            999,
            1_000_000_000L,
            10_000_000_000L
        )
        assertEquals(32768, key.contextTokens)
        assertEquals("gpu", key.backendKey)
        assertEquals(999, key.gpuLayers)
    }

    @Test
    fun sessionCacheReusesByRequestedKey() {
        val cache = GgufSessionCache {}
        val requested = GgufModelKey("/models/model.gguf", 65536, "gpu", 999)
        val effective = GgufModelKey("/models/model.gguf", 32768, "gpu", 999)
        cache.replace(requested, effective, 123L)
        assertEquals(123L, cache.get(requested))
        assertEquals(0L, cache.get(effective))
    }

    @Test
    fun sessionCacheClosesOldHandleOnReplace() {
        var closed = 0L
        val cache = GgufSessionCache { h -> closed = h }
        val key = GgufModelKey("/models/model.gguf", 4096, "cpu", 0)
        cache.replace(key, key, 1L)
        cache.replace(key, key, 2L)
        assertEquals(1L, closed)
        assertEquals(2L, cache.get(key))
    }

    @Test
    fun sessionCacheClosesOnClose() {
        var closed = 0L
        val cache = GgufSessionCache { h -> closed = h }
        val key = GgufModelKey("/models/model.gguf", 4096, "cpu", 0)
        cache.replace(key, key, 7L)
        cache.close()
        assertEquals(7L, closed)
    }

    @Test(expected = IllegalStateException::class)
    fun sessionCacheRejectsAccessAfterClose() {
        val cache = GgufSessionCache {}
        val key = GgufModelKey("/models/model.gguf", 4096, "cpu", 0)
        cache.close()
        cache.get(key)
    }

    @Test
    fun sessionCacheInvalidateClosesAndClearsHandle() {
        var closed = 0L
        val cache = GgufSessionCache { h -> closed = h }
        val key = GgufModelKey("/models/model.gguf", 4096, "gpu", 999)
        cache.replace(key, key, 5L)
        cache.invalidate()
        assertEquals(5L, closed)
        assertEquals(0L, cache.get(key))
    }

    @Test
    fun sessionCacheInvalidateIsSafeWhenClosed() {
        val cache = GgufSessionCache {}
        cache.close()
        cache.invalidate()
    }

    @Test
    fun vulkanFallbackStateDefaultsToFalse() {
        assertEquals(false, GgufVulkanFallbackState.isGpuDisabled)
    }

    @Test
    fun vulkanFallbackStateCanBeSetAndReset() {
        GgufVulkanFallbackState.isGpuDisabled = true
        assertEquals(true, GgufVulkanFallbackState.isGpuDisabled)
        GgufVulkanFallbackState.isGpuDisabled = false
        assertEquals(false, GgufVulkanFallbackState.isGpuDisabled)
    }
}
