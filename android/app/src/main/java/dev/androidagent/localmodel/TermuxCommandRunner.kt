package dev.androidagent.localmodel

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxCommandRunner(private val context: Context) {
    suspend fun run(
        command: String,
        workdir: String,
        timeoutMs: Long
    ): JSONObject {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return JSONObject().put("ok", false).put("error", "No Termux command supplied.")
        }
        if (!isTermuxInstalled()) {
            return setupError("Termux is not installed or is not visible to OpenAgent.")
        }
        if (!hasRunCommandPermission()) {
            return setupError("OpenAgent does not have Termux RUN_COMMAND permission. Grant it in Android Settings > Apps > OpenAgent > Permissions > Additional permissions.")
        }

        val executionId = nextExecutionId.incrementAndGet()
        val execution = TermuxExecutionLifecycle(executionId.toString())
        val result = CompletableDeferred<TermuxCommandResult>()
        pendingResults[executionId] = result

        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(EXTRA_EXECUTION_ID, executionId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            executionId,
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )

        val runIntent = Intent(ACTION_RUN_COMMAND)
            .setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            .putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            .putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", trimmed))
            .putExtra(EXTRA_WORKDIR, workdir.ifBlank { TERMUX_HOME })
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
            .putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            .putExtra(EXTRA_COMMAND_LABEL, "OpenAgent local command")
            .putExtra(EXTRA_COMMAND_DESCRIPTION, "Command requested by the local OpenAgent agent.")

        return try {
            execution.markLaunchRequested()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(runIntent)
            } else {
                context.startService(runIntent)
            }
            execution.markAwaitingResult()
            when (val outcome = awaitTermuxResult(timeoutMs) { result.await() }) {
                is TermuxAwaitOutcome.Completed -> {
                    execution.settle(TermuxExecutionOutcome.Completed)
                    outcome.value.toJson(trimmed, workdir.ifBlank { TERMUX_HOME })
                }
                TermuxAwaitOutcome.TimedOut -> {
                    execution.requestCancellation(TermuxCancellationReason.TIMEOUT)
                    execution.settle(TermuxExecutionOutcome.Cancelled(TermuxCancellationReason.TIMEOUT, killVerified = false))
                    setupError("Timed out waiting for Termux command output. Cancellation of the external Termux process was not verified, so it may still be running.")
                }
            }
        } catch (error: CancellationException) {
            execution.requestCancellation(TermuxCancellationReason.COROUTINE_CANCELLED)
            execution.settle(TermuxExecutionOutcome.Cancelled(TermuxCancellationReason.COROUTINE_CANCELLED, killVerified = false))
            throw error
        } catch (error: SecurityException) {
            execution.settle(TermuxExecutionOutcome.Failed(error.message ?: "RUN_COMMAND permission denied"))
            setupError("OpenAgent does not have Termux RUN_COMMAND permission. Grant it in Android Settings > Apps > OpenAgent > Permissions > Additional permissions.")
        } catch (error: IllegalStateException) {
            execution.settle(TermuxExecutionOutcome.Failed(error.message ?: "Termux service start refused"))
            setupError("Android refused to start Termux. Open Termux once, disable battery restrictions if needed, and ensure allow-external-apps=true in ~/.termux/termux.properties.")
        } catch (error: Throwable) {
            execution.settle(TermuxExecutionOutcome.Failed(error.message ?: error.toString()))
            JSONObject()
                .put("ok", false)
                .put("command", trimmed)
                .put("workdir", workdir.ifBlank { TERMUX_HOME })
                .put("error", error.message ?: error.toString())
        } finally {
            pendingResults.remove(executionId)
        }
    }

    private fun setupError(message: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", message)
            .put("setup", "Termux must be installed from F-Droid/GitHub, OpenAgent must have com.termux.permission.RUN_COMMAND, and Termux must set allow-external-apps=true.")

    private fun isTermuxInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess

    private fun hasRunCommandPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

        private const val EXTRA_EXECUTION_ID = "app.lynk.termux.EXECUTION_ID"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val RUNNER_APP_SHELL = "app-shell"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"

        private val nextExecutionId = AtomicInteger(1000)
        private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

        internal fun completeResult(intent: Intent?) {
            if (intent == null) return
            val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            pendingResults.remove(executionId)?.complete(TermuxCommandResult.from(resultBundle))
        }

        private fun mutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        internal fun resultFromBundle(bundle: Bundle?): TermuxCommandResult =
            TermuxCommandResult.from(bundle)
    }

    data class TermuxCommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val termuxErrorCode: Int,
        val termuxErrorMessage: String
    ) {
        fun toJson(command: String, workdir: String): JSONObject {
            val internalOk = termuxErrorCode == Activity.RESULT_OK || termuxErrorCode == 0
            val shellOk = exitCode == 0
            return JSONObject()
                .put("ok", internalOk && shellOk)
                .put("command", command)
                .put("workdir", workdir)
                .put("exitCode", exitCode)
                .put("stdout", stdout.take(MAX_OUTPUT_CHARS))
                .put("stderr", stderr.take(MAX_OUTPUT_CHARS))
                .put("termuxErrorCode", termuxErrorCode)
                .put("termuxErrorMessage", termuxErrorMessage.take(MAX_OUTPUT_CHARS))
                .apply {
                    if (!internalOk && termuxErrorMessage.isNotBlank()) put("error", termuxErrorMessage)
                    if (internalOk && !shellOk) put("error", stderr.ifBlank { stdout }.take(MAX_OUTPUT_CHARS))
                }
        }

        companion object {
            private const val MAX_OUTPUT_CHARS = 24_000

            fun from(bundle: Bundle?): TermuxCommandResult {
                if (bundle == null) {
                    return TermuxCommandResult("", "", -1, -1, "Termux did not return a result bundle.")
                }
                return TermuxCommandResult(
                    stdout = bundle.getString(RESULT_STDOUT, ""),
                    stderr = bundle.getString(RESULT_STDERR, ""),
                    exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
                    termuxErrorCode = bundle.getInt(RESULT_ERR, -1),
                    termuxErrorMessage = bundle.getString(RESULT_ERRMSG, "")
                )
            }
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        TermuxCommandRunner.completeResult(intent)
    }
}
