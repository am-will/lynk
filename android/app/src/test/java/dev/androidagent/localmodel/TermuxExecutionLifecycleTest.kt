package dev.androidagent.localmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TermuxExecutionLifecycleTest {
    @Test
    fun modelsLaunchRunningCancellationAndSettlementExplicitly() {
        val lifecycle = TermuxExecutionLifecycle("exec-1")
        val process = TermuxProcessIdentity(
            pid = 101,
            processGroupId = 101,
            startTimeTicks = 5_000,
            nonce = "nonce"
        )

        assertSame(TermuxExecutionState.Created, lifecycle.state())
        lifecycle.markLaunchRequested()
        assertSame(TermuxExecutionState.LaunchRequested, lifecycle.state())
        lifecycle.markAwaitingResult()
        assertSame(TermuxExecutionState.AwaitingResult, lifecycle.state())
        assertFalse(lifecycle.markRunning(process))
        assertEquals(TermuxExecutionState.Running(process), lifecycle.state())
        assertTrue(lifecycle.requestCancellation(TermuxCancellationReason.SESSION_STOPPED))
        assertEquals(
            TermuxExecutionState.CancellationRequested(TermuxCancellationReason.SESSION_STOPPED, process),
            lifecycle.state()
        )
        assertTrue(lifecycle.markKillRequested())
        assertEquals(
            TermuxExecutionState.KillRequested(TermuxCancellationReason.SESSION_STOPPED, process),
            lifecycle.state()
        )
        assertTrue(lifecycle.settle(TermuxExecutionOutcome.Cancelled(TermuxCancellationReason.SESSION_STOPPED, true)))
        assertFalse(lifecycle.settle(TermuxExecutionOutcome.Completed))
    }

    @Test
    fun cancellationBeforeProcessIdentityIsPreservedWhenStartArrivesLate() {
        val lifecycle = TermuxExecutionLifecycle("exec-2")
        val process = TermuxProcessIdentity(202, 202, 6_000, "nonce")

        lifecycle.markLaunchRequested()
        assertTrue(lifecycle.requestCancellation(TermuxCancellationReason.COROUTINE_CANCELLED))
        assertTrue(lifecycle.markRunning(process))

        assertEquals(
            TermuxExecutionState.CancellationRequested(TermuxCancellationReason.COROUTINE_CANCELLED, process),
            lifecycle.state()
        )
        assertFalse(lifecycle.requestCancellation(TermuxCancellationReason.TIMEOUT))
    }

    @Test
    fun timeoutIsAnExplicitAwaitOutcome() = runBlocking {
        val outcome = awaitTermuxResult(timeoutMs = 10) { awaitCancellation() }

        assertSame(TermuxAwaitOutcome.TimedOut, outcome)
    }

    @Test
    fun parentCancellationIsNotConvertedIntoAToolOutcome() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val awaiting = async {
            awaitTermuxResult(timeoutMs = 60_000) { never.await() }
        }
        yield()

        awaiting.cancel(CancellationException("session stopped"))

        try {
            awaiting.await()
            fail("Expected parent cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("session stopped", error.message)
        }
    }
}
