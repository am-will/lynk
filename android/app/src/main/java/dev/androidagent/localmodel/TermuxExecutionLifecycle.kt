package dev.androidagent.localmodel

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal enum class TermuxCancellationReason {
    COROUTINE_CANCELLED,
    TIMEOUT,
    START_FAILED,
    SESSION_STOPPED,
    SERVICE_DESTROYED
}

internal data class TermuxProcessIdentity(
    val pid: Long,
    val processGroupId: Long,
    val startTimeTicks: Long,
    val nonce: String
)

internal sealed interface TermuxExecutionState {
    data object Created : TermuxExecutionState
    data object LaunchRequested : TermuxExecutionState
    data object AwaitingResult : TermuxExecutionState
    data class Running(val process: TermuxProcessIdentity) : TermuxExecutionState
    data class CancellationRequested(
        val reason: TermuxCancellationReason,
        val process: TermuxProcessIdentity?
    ) : TermuxExecutionState
    data class KillRequested(
        val reason: TermuxCancellationReason,
        val process: TermuxProcessIdentity?
    ) : TermuxExecutionState
    data class Settled(val outcome: TermuxExecutionOutcome) : TermuxExecutionState
}

internal sealed interface TermuxExecutionOutcome {
    data object Completed : TermuxExecutionOutcome
    data class Failed(val message: String) : TermuxExecutionOutcome
    data class Cancelled(
        val reason: TermuxCancellationReason,
        val killVerified: Boolean
    ) : TermuxExecutionOutcome
}

internal class TermuxExecutionLifecycle(val executionId: String) {
    private var current: TermuxExecutionState = TermuxExecutionState.Created

    @Synchronized
    fun state(): TermuxExecutionState = current

    @Synchronized
    fun markLaunchRequested() {
        check(current is TermuxExecutionState.Created) {
            "Termux execution $executionId cannot launch from ${current.javaClass.simpleName}."
        }
        current = TermuxExecutionState.LaunchRequested
    }

    @Synchronized
    fun markAwaitingResult() {
        check(current is TermuxExecutionState.LaunchRequested) {
            "Termux execution $executionId cannot await a result from ${current.javaClass.simpleName}."
        }
        current = TermuxExecutionState.AwaitingResult
    }

    @Synchronized
    fun markRunning(process: TermuxProcessIdentity): Boolean {
        current = when (val state = current) {
            is TermuxExecutionState.LaunchRequested,
            is TermuxExecutionState.AwaitingResult -> TermuxExecutionState.Running(process)
            is TermuxExecutionState.CancellationRequested -> state.copy(process = process)
            is TermuxExecutionState.KillRequested -> state.copy(process = process)
            else -> error("Termux execution $executionId cannot become running from ${state.javaClass.simpleName}.")
        }
        return current is TermuxExecutionState.CancellationRequested || current is TermuxExecutionState.KillRequested
    }

    @Synchronized
    fun requestCancellation(reason: TermuxCancellationReason): Boolean {
        current = when (val state = current) {
            is TermuxExecutionState.Settled -> return false
            is TermuxExecutionState.CancellationRequested,
            is TermuxExecutionState.KillRequested -> return false
            is TermuxExecutionState.Running -> TermuxExecutionState.CancellationRequested(reason, state.process)
            else -> TermuxExecutionState.CancellationRequested(reason, null)
        }
        return true
    }

    @Synchronized
    fun markKillRequested(): Boolean {
        val state = current as? TermuxExecutionState.CancellationRequested ?: return false
        current = TermuxExecutionState.KillRequested(state.reason, state.process)
        return true
    }

    @Synchronized
    fun settle(outcome: TermuxExecutionOutcome): Boolean {
        if (current is TermuxExecutionState.Settled) return false
        current = TermuxExecutionState.Settled(outcome)
        return true
    }
}

internal sealed interface TermuxAwaitOutcome<out T> {
    data class Completed<T>(val value: T) : TermuxAwaitOutcome<T>
    data object TimedOut : TermuxAwaitOutcome<Nothing>
}

internal suspend fun <T> awaitTermuxResult(
    timeoutMs: Long,
    awaitResult: suspend () -> T
): TermuxAwaitOutcome<T> = try {
    TermuxAwaitOutcome.Completed(withTimeout(timeoutMs) { awaitResult() })
} catch (_: TimeoutCancellationException) {
    TermuxAwaitOutcome.TimedOut
}
