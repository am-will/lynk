package dev.androidagent.localmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TermuxCommandRunnerTest {
    @Test
    fun cancelBeforeStartIsNonceBoundAndSettlesOnce() = runBlocking {
        val gateway = FakeTermuxGateway().apply {
            autoCompleteStart = false
            cancelStatus = "armed"
        }
        val runner = TermuxCommandRunner(gateway)
        val observed = CompletableDeferred<TermuxCommandCancellationException>()
        val job = launch {
            try {
                runner.run("echo should-not-start", "/tmp", 60_000, "owner")
            } catch (error: TermuxCommandCancellationException) {
                observed.complete(error)
                throw error
            }
        }
        waitUntil { gateway.userWrappers.size == 1 && gateway.startControls.size == 1 }

        job.cancel(CancellationException("stop before start"))
        val cancellation = withTimeout(2_000) { observed.await() }
        job.join()

        assertTrue(cancellation.terminationVerified)
        assertEquals("armed", cancellation.terminationStatus)
        val executionId = gateway.userWrappers.single().executionId
        assertEquals(1, gateway.cancelControls.count { it.executionId == executionId })
        assertEquals(1, gateway.closedUserHandles(executionId))
    }

    @Test
    fun cancelAfterStartKillsTheRecordedProcessGroupAndPropagatesCancellation() = runBlocking {
        val gateway = FakeTermuxGateway()
        val runner = TermuxCommandRunner(gateway)
        val observed = CompletableDeferred<TermuxCommandCancellationException>()
        val job = launch {
            try {
                runner.run("sleep 60", "/tmp", 60_000, "owner")
            } catch (error: TermuxCommandCancellationException) {
                observed.complete(error)
                throw error
            }
        }
        waitUntil { gateway.userWrappers.size == 1 && gateway.startControls.size == 1 }

        job.cancel(CancellationException("stop after start"))
        val cancellation = withTimeout(2_000) { observed.await() }
        job.join()

        assertTrue(cancellation.terminationVerified)
        assertEquals("verified", cancellation.terminationStatus)
        assertEquals(
            gateway.userWrappers.single().nonce,
            gateway.cancelControls.single { it.executionId == gateway.userWrappers.single().executionId }.nonce
        )
    }

    @Test
    fun timeoutReturnsTruthfulVerifiedCancellationStatus() = runBlocking {
        val gateway = FakeTermuxGateway()
        val runner = TermuxCommandRunner(gateway)

        val result = runner.run("sleep 60", "/tmp", 20, "owner")

        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getBoolean("cancellationVerified"))
        assertEquals("verified", result.getString("cancellationStatus"))
        assertTrue(result.getString("error").contains("termination was verified"))
    }

    @Test
    fun lateCommandResultAfterCancellationCannotProduceASecondOutcome() = runBlocking {
        val gateway = FakeTermuxGateway().apply { completeWrapperOnKill = false }
        val runner = TermuxCommandRunner(gateway)
        val outcomes = AtomicInteger(0)
        val observed = CompletableDeferred<TermuxCommandCancellationException>()
        val job = launch {
            try {
                runner.run("sleep 60", "/tmp", 60_000, "owner")
                outcomes.incrementAndGet()
            } catch (error: TermuxCommandCancellationException) {
                outcomes.incrementAndGet()
                observed.complete(error)
                throw error
            }
        }
        waitUntil { gateway.userWrappers.size == 1 }
        val executionId = gateway.userWrappers.single().executionId

        job.cancel(CancellationException("stop"))
        withTimeout(2_000) { observed.await() }
        job.join()
        assertTrue(gateway.completeUser(executionId, successResult("late")))
        delay(20)

        assertEquals(1, outcomes.get())
        assertEquals(1, gateway.closedUserHandles(executionId))
    }

    @Test
    fun killFailureIsReportedAsUnverifiedInsteadOfClaimingStop() = runBlocking {
        val gateway = FakeTermuxGateway().apply {
            killSucceeds = false
            completeWrapperOnKill = false
        }
        val runner = TermuxCommandRunner(gateway)
        val observed = CompletableDeferred<TermuxCommandCancellationException>()
        val job = launch {
            try {
                runner.run("sleep 60", "/tmp", 60_000, "owner")
            } catch (error: TermuxCommandCancellationException) {
                observed.complete(error)
                throw error
            }
        }
        waitUntil { gateway.userWrappers.size == 1 }

        job.cancel(CancellationException("stop"))
        val cancellation = withTimeout(2_000) { observed.await() }
        job.join()

        assertFalse(cancellation.terminationVerified)
        assertEquals("failed", cancellation.terminationStatus)
        assertTrue(cancellation.message.orEmpty().contains("may still be running"))
    }

    @Test
    fun concurrentOwnersAreIsolatedAndShareOneCancellationPreflight() = runBlocking {
        val gateway = FakeTermuxGateway().apply { completeWrapperOnKill = true }
        val runner = TermuxCommandRunner(gateway)
        val first = async { runner.run("first", "/tmp", 60_000, "owner-a") }
        waitUntil { gateway.userWrappers.size == 1 }
        val firstId = gateway.userWrappers[0].executionId
        val second = async { runner.run("second", "/tmp", 60_000, "owner-b") }
        waitUntil { gateway.userWrappers.size == 2 }
        val secondId = gateway.userWrappers[1].executionId

        assertEquals(1, runner.cancelOwner("owner-a"))
        gateway.completeUser(secondId, successResult("second done"))
        val firstResult = first.await()
        val secondResult = second.await()

        assertFalse(firstResult.getBoolean("ok"))
        assertTrue(firstResult.getBoolean("cancellationVerified"))
        assertTrue(secondResult.getBoolean("ok"))
        assertEquals("second done", secondResult.getString("stdout"))
        assertEquals(1, gateway.cancelControls.count { it.executionId == firstId })
        assertEquals(0, gateway.cancelControls.count { it.executionId == secondId })
        assertEquals(1, gateway.probeKillCount)
    }

    @Test
    fun serviceDestructionRequestsOneKillForEveryActiveCommand() = runBlocking {
        val gateway = FakeTermuxGateway().apply { completeWrapperOnKill = true }
        val runner = TermuxCommandRunner(gateway)
        val first = async { runner.run("first", "/tmp", 60_000, "owner-a") }
        waitUntil { gateway.userWrappers.size == 1 }
        val second = async { runner.run("second", "/tmp", 60_000, "owner-b") }
        waitUntil { gateway.userWrappers.size == 2 }

        assertEquals(2, runner.cancelAll(TermuxCancellationReason.SERVICE_DESTROYED))
        assertFalse(first.await().getBoolean("ok"))
        assertFalse(second.await().getBoolean("ok"))
        assertEquals(2, gateway.cancelControls.size)
    }

    @Test
    fun failedLiveKillPreflightBlocksUserCommandsFailClosed() = runBlocking {
        val gateway = FakeTermuxGateway().apply { probeKillSucceeds = false }
        val runner = TermuxCommandRunner(gateway)

        val result = runner.run("touch /tmp/must-not-run", "/tmp", 60_000, "owner")

        assertFalse(result.getBoolean("ok"))
        assertEquals("cancellation_unavailable", result.getString("status"))
        assertFalse(result.getBoolean("cancellationVerified"))
        assertTrue(gateway.userWrappers.isEmpty())
        assertEquals(1, gateway.probeWrappers.size)
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(1)
        }
    }

    private class FakeTermuxGateway : TermuxRunCommandGateway {
        @Volatile var autoCompleteStart = true
        @Volatile var killSucceeds = true
        @Volatile var probeKillSucceeds = true
        @Volatile var completeWrapperOnKill = true
        @Volatile var cancelStatus = "verified"
        val userWrappers = CopyOnWriteArrayList<FakeExecution>()
        val probeWrappers = CopyOnWriteArrayList<FakeExecution>()
        val startControls = CopyOnWriteArrayList<FakeExecution>()
        val cancelControls = CopyOnWriteArrayList<FakeExecution>()
        @Volatile var probeKillCount = 0

        private val nextRequestId = AtomicInteger()
        private val nextPid = AtomicInteger(4_000)
        private val wrappers = ConcurrentHashMap<String, FakeHandle>()

        override fun availability(): TermuxAvailability = TermuxAvailability.Available

        @Synchronized
        override fun start(request: TermuxRunCommandRequest): TermuxCommandHandle {
            val handle = FakeHandle(nextRequestId.incrementAndGet())
            val kind = request.arguments.getOrNull(2)
            if (kind == "lynk-run-wrapper") {
                val execution = FakeExecution(
                    executionId = request.arguments[3],
                    nonce = request.arguments[4],
                    command = request.arguments[5],
                    pid = nextPid.incrementAndGet()
                )
                wrappers[execution.executionId] = handle
                if (execution.command.contains("/sleep 30")) {
                    probeWrappers += execution
                } else {
                    userWrappers += execution
                }
                return handle
            }
            check(kind == "lynk-run-control")
            val operation = request.arguments[3]
            val executionId = request.arguments[4]
            val nonce = request.arguments[5]
            val execution = execution(executionId, nonce)
            when (operation) {
                "start" -> {
                    startControls += execution
                    if (autoCompleteStart) {
                        handle.complete(controlResult("START", "verified", execution))
                    }
                }
                "kill-running" -> {
                    probeKillCount += 1
                    if (probeKillSucceeds) {
                        handle.complete(controlResult("KILL", "verified", execution))
                    } else {
                        handle.complete(controlResult("KILL", "failed", execution, exitCode = 75, detail = "probe-failed"))
                    }
                    wrappers[executionId]?.complete(killedResult())
                }
                "cancel" -> {
                    cancelControls += execution
                    if (killSucceeds) {
                        handle.complete(controlResult("KILL", cancelStatus, execution))
                        if (completeWrapperOnKill) wrappers[executionId]?.complete(killedResult())
                    } else {
                        handle.complete(controlResult("KILL", "failed", execution, exitCode = 75, detail = "group-still-alive"))
                    }
                }
                else -> error("Unexpected operation $operation")
            }
            return handle
        }

        fun completeUser(executionId: String, result: TermuxCommandResult): Boolean =
            wrappers.getValue(executionId).complete(result)

        fun closedUserHandles(executionId: String): Int =
            if (wrappers.getValue(executionId).closed) 1 else 0

        private fun execution(executionId: String, nonce: String): FakeExecution {
            val known = (probeWrappers + userWrappers).first { it.executionId == executionId }
            check(known.nonce == nonce)
            return known
        }

        private fun controlResult(
            marker: String,
            status: String,
            execution: FakeExecution,
            exitCode: Int = 0,
            detail: String? = null
        ): TermuxCommandResult {
            val processFields = if (status == "verified") {
                " ${execution.pid} ${execution.pid} ${execution.pid * 10L}"
            } else {
                ""
            }
            val suffix = detail?.let { " $it" }.orEmpty()
            return TermuxCommandResult(
                stdout = "LYNK_$marker $status ${execution.executionId}$processFields$suffix\n",
                stderr = "",
                exitCode = exitCode,
                termuxErrorCode = 0,
                termuxErrorMessage = ""
            )
        }

        data class FakeExecution(
            val executionId: String,
            val nonce: String,
            val command: String,
            val pid: Int
        )

        private class FakeHandle(
            override val requestId: Int
        ) : TermuxCommandHandle {
            private val result = CompletableDeferred<TermuxCommandResult>()
            @Volatile var closed = false

            override suspend fun awaitResult(): TermuxCommandResult = result.await()
            fun complete(value: TermuxCommandResult): Boolean = result.complete(value)
            override fun close() {
                closed = true
            }
        }
    }

    companion object {
        private fun successResult(stdout: String) = TermuxCommandResult(stdout, "", 0, 0, "")
        private fun killedResult() = TermuxCommandResult("", "killed", 137, 0, "")
    }
}
