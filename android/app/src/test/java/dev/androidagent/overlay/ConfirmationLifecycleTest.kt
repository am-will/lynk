package dev.androidagent.overlay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationLifecycleTest {
    @Test
    fun secondAskCompletesFirstAsCancelled() {
        val lifecycle = ConfirmationLifecycle()
        val first = lifecycle.begin()
        val second = lifecycle.begin()

        assertTrue(first.isCompleted)
        assertEquals(false, runBlocking { first.await() })
        assertFalse(second.isCompleted)
    }

    @Test
    fun dismissCompletesActiveAsCancelled() {
        val lifecycle = ConfirmationLifecycle()
        val deferred = lifecycle.begin()

        lifecycle.dismiss()

        assertTrue(deferred.isCompleted)
        assertEquals(false, runBlocking { deferred.await() })
    }

    @Test
    fun allowCompletesActiveAsConfirmed() {
        val lifecycle = ConfirmationLifecycle()
        val deferred = lifecycle.begin()

        lifecycle.allow()

        assertTrue(deferred.isCompleted)
        assertEquals(true, runBlocking { deferred.await() })
    }

    @Test
    fun cancelCompletesActiveAsCancelled() {
        val lifecycle = ConfirmationLifecycle()
        val deferred = lifecycle.begin()

        lifecycle.cancel()

        assertTrue(deferred.isCompleted)
        assertEquals(false, runBlocking { deferred.await() })
    }
}
