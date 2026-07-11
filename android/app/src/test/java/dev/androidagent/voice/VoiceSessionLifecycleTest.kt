package dev.androidagent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionLifecycleTest {
    @Test
    fun terminalOwnershipIsExactOnceAndStaleGenerationsAreRejected() {
        val lifecycle = VoiceSessionLifecycle()
        val first = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.activate(first))
        assertTrue(lifecycle.beginStop(first))
        assertFalse(lifecycle.beginStop(first))
        assertTrue(lifecycle.finishStop(first))

        val second = requireNotNull(lifecycle.begin())
        assertNotEquals(first, second)
        assertFalse(lifecycle.activate(first))
        assertFalse(lifecycle.beginStop(first))
        assertTrue(lifecycle.owns(second))
    }

    @Test
    fun failureCanBeRetriedOnlyAfterCleanupFinishes() {
        val lifecycle = VoiceSessionLifecycle()
        val generation = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.beginStop(generation))
        assertFalse(lifecycle.begin() != null)
        assertTrue(lifecycle.finishStop(generation, "failed"))
        assertTrue(lifecycle.begin() != null)
    }
}
