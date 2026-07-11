package dev.androidagent.localmodel

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class TermuxCommandRunner private constructor(
    private val gateway: TermuxRunCommandGateway,
    private val identityFactory: () -> TermuxExecutionIdentity
) {
    constructor(context: Context) : this(
        gateway = AndroidTermuxRunCommandGateway(context.applicationContext),
        identityFactory = TermuxExecutionIdentity::create
    )

    internal constructor(
        gateway: TermuxRunCommandGateway
    ) : this(gateway, TermuxExecutionIdentity::create)

    private val activeExecutions = ConcurrentHashMap<String, ActiveTermuxExecution>()

    suspend fun run(
        command: String,
        workdir: String,
        timeoutMs: Long
    ): JSONObject {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return JSONObject().put("ok", false).put("error", "No Termux command supplied.")
        }
        when (gateway.availability()) {
            TermuxAvailability.Available -> Unit
            TermuxAvailability.NotInstalled ->
                return setupError("Termux is not installed or is not visible to Lynk.")
            TermuxAvailability.PermissionMissing ->
                return setupError("Lynk does not have Termux RUN_COMMAND permission. Grant it in Android Settings > Apps > Lynk > Permissions > Additional permissions.")
        }

        val identity = identityFactory()
        val lifecycle = TermuxExecutionLifecycle(identity.executionId)
        val execution = ActiveTermuxExecution(identity, lifecycle)
        activeExecutions[identity.executionId] = execution
        val resolvedWorkdir = workdir.ifBlank { TermuxExecutionProtocol.TERMUX_HOME }

        return try {
            lifecycle.markLaunchRequested()
            val commandHandle = gateway.start(
                TermuxExecutionProtocol.wrappedCommand(identity, trimmed, resolvedWorkdir)
            )
            execution.commandHandle = commandHandle
            lifecycle.markAwaitingResult()

            val start = runControl(TermuxExecutionProtocol.startControl(identity), START_CONTROL_TIMEOUT_MS)
            val startResult = start.result
            val startControl = start.control
            if (
                startResult == null ||
                !startResult.succeeded ||
                startControl?.operation != "start" ||
                startControl.status != "verified" ||
                startControl.executionId != identity.executionId ||
                startControl.process == null
            ) {
                val kill = killExecution(execution, TermuxCancellationReason.START_FAILED)
                lifecycle.settle(TermuxExecutionOutcome.Failed("Termux command tracking could not be verified before execution."))
                return trackingFailure(trimmed, resolvedWorkdir, identity.executionId, start.detail, kill)
            }
            lifecycle.markRunning(startControl.process.copy(nonce = identity.nonce))

            when (val outcome = awaitTermuxResult(timeoutMs) { commandHandle.awaitResult() }) {
                is TermuxAwaitOutcome.Completed -> {
                    lifecycle.settle(TermuxExecutionOutcome.Completed)
                    outcome.value.toJson(trimmed, resolvedWorkdir)
                        .put("executionId", identity.executionId)
                        .put("cancellationSupported", true)
                }
                TermuxAwaitOutcome.TimedOut -> {
                    val kill = killExecution(execution, TermuxCancellationReason.TIMEOUT)
                    lifecycle.settle(
                        TermuxExecutionOutcome.Cancelled(
                            TermuxCancellationReason.TIMEOUT,
                            kill.verified
                        )
                    )
                    cancellationResult(
                        command = trimmed,
                        workdir = resolvedWorkdir,
                        identity = identity,
                        message = "Termux command timed out.",
                        kill = kill
                    )
                }
            }
        } catch (error: CancellationException) {
            val kill = withContext(NonCancellable) {
                killExecution(execution, TermuxCancellationReason.COROUTINE_CANCELLED)
            }
            lifecycle.settle(
                TermuxExecutionOutcome.Cancelled(
                    TermuxCancellationReason.COROUTINE_CANCELLED,
                    kill.verified
                )
            )
            throw error
        } catch (error: SecurityException) {
            lifecycle.settle(TermuxExecutionOutcome.Failed(error.message ?: "RUN_COMMAND permission denied"))
            setupError("Lynk does not have Termux RUN_COMMAND permission. Grant it in Android Settings > Apps > Lynk > Permissions > Additional permissions.")
        } catch (error: IllegalStateException) {
            lifecycle.settle(TermuxExecutionOutcome.Failed(error.message ?: "Termux service start refused"))
            setupError("Android refused to start Termux. Open Termux once, disable battery restrictions if needed, and ensure allow-external-apps=true in ~/.termux/termux.properties.")
        } catch (error: Throwable) {
            lifecycle.settle(TermuxExecutionOutcome.Failed(error.message ?: error.toString()))
            JSONObject()
                .put("ok", false)
                .put("command", trimmed)
                .put("workdir", resolvedWorkdir)
                .put("executionId", identity.executionId)
                .put("error", error.message ?: error.toString())
        } finally {
            execution.commandHandle?.close()
            activeExecutions.remove(identity.executionId, execution)
        }
    }

    private suspend fun killExecution(
        execution: ActiveTermuxExecution,
        reason: TermuxCancellationReason
    ): KillAttempt {
        execution.lifecycle.requestCancellation(reason)
        execution.lifecycle.markKillRequested()
        val control = runControl(
            TermuxExecutionProtocol.cancelControl(execution.identity),
            KILL_CONTROL_TIMEOUT_MS
        )
        val parsed = control.control
        val verified = control.result?.succeeded == true &&
            parsed?.operation == "kill" &&
            parsed.executionId == execution.identity.executionId &&
            parsed.verified
        return KillAttempt(
            verified = verified,
            status = parsed?.status ?: "unverified",
            detail = parsed?.detail ?: control.detail
        )
    }

    private suspend fun runControl(
        request: TermuxRunCommandRequest,
        timeoutMs: Long
    ): ControlAttempt {
        return try {
            gateway.start(request).use { handle ->
                when (val outcome = awaitTermuxResult(timeoutMs) { handle.awaitResult() }) {
                    is TermuxAwaitOutcome.Completed -> ControlAttempt(
                        result = outcome.value,
                        control = TermuxExecutionProtocol.parseControlResult(outcome.value.stdout),
                        detail = outcome.value.termuxErrorMessage
                            .ifBlank { outcome.value.stderr }
                            .takeIf { it.isNotBlank() }
                    )
                    TermuxAwaitOutcome.TimedOut -> ControlAttempt(
                        result = null,
                        control = null,
                        detail = "Termux control command timed out."
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ControlAttempt(result = null, control = null, detail = error.message ?: error.toString())
        }
    }

    private fun trackingFailure(
        command: String,
        workdir: String,
        executionId: String,
        detail: String?,
        kill: KillAttempt
    ): JSONObject = JSONObject()
        .put("ok", false)
        .put("command", command)
        .put("workdir", workdir)
        .put("executionId", executionId)
        .put("error", "Termux command was not allowed to start because tracked cancellation could not be verified.")
        .put("trackingVerified", false)
        .put("cancellationVerified", kill.verified)
        .put("cancellationStatus", kill.status)
        .apply {
            detail?.let { put("trackingError", it) }
            kill.detail?.let { put("cancellationError", it) }
        }

    private fun cancellationResult(
        command: String,
        workdir: String,
        identity: TermuxExecutionIdentity,
        message: String,
        kill: KillAttempt
    ): JSONObject = JSONObject()
        .put("ok", false)
        .put("command", command)
        .put("workdir", workdir)
        .put("executionId", identity.executionId)
        .put("error", if (kill.verified) "$message External process-group termination was verified." else "$message External process-group termination could not be verified; the command may still be running.")
        .put("cancellationVerified", kill.verified)
        .put("cancellationStatus", kill.status)
        .apply { kill.detail?.let { put("cancellationError", it) } }

    private fun setupError(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", message)
        .put("setup", "Termux must be installed from F-Droid/GitHub, Lynk must have com.termux.permission.RUN_COMMAND, and Termux must set allow-external-apps=true.")

    private data class ActiveTermuxExecution(
        val identity: TermuxExecutionIdentity,
        val lifecycle: TermuxExecutionLifecycle,
        @Volatile var commandHandle: TermuxCommandHandle? = null
    )

    private data class ControlAttempt(
        val result: TermuxCommandResult?,
        val control: TermuxControlResult?,
        val detail: String?
    )

    private data class KillAttempt(
        val verified: Boolean,
        val status: String,
        val detail: String?
    )

    companion object {
        private const val START_CONTROL_TIMEOUT_MS = 10_000L
        private const val KILL_CONTROL_TIMEOUT_MS = 10_000L

        internal fun resultFromBundle(bundle: android.os.Bundle?): TermuxCommandResult =
            TermuxCommandResult.from(bundle)
    }
}
