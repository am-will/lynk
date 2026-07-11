package dev.androidagent.localmodel

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface TermuxAvailability {
    data object Available : TermuxAvailability
    data object NotInstalled : TermuxAvailability
    data object PermissionMissing : TermuxAvailability
}

internal interface TermuxCommandHandle : AutoCloseable {
    val requestId: Int
    suspend fun awaitResult(): TermuxCommandResult
}

internal interface TermuxRunCommandGateway {
    fun availability(): TermuxAvailability
    fun start(request: TermuxRunCommandRequest): TermuxCommandHandle
}

internal class AndroidTermuxRunCommandGateway(
    private val context: Context
) : TermuxRunCommandGateway {
    override fun availability(): TermuxAvailability {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        if (!installed) return TermuxAvailability.NotInstalled
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return if (permissionGranted) TermuxAvailability.Available else TermuxAvailability.PermissionMissing
    }

    override fun start(request: TermuxRunCommandRequest): TermuxCommandHandle {
        val requestId = nextRequestId.incrementAndGet()
        val result = CompletableDeferred<TermuxCommandResult>()
        pendingResults[requestId] = result
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestId,
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val runIntent = Intent(ACTION_RUN_COMMAND)
            .setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            .putExtra(EXTRA_COMMAND_PATH, request.commandPath)
            .putExtra(EXTRA_ARGUMENTS, request.arguments)
            .putExtra(EXTRA_WORKDIR, request.workdir)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
            .putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            .putExtra(EXTRA_COMMAND_LABEL, request.label)
            .putExtra(EXTRA_COMMAND_DESCRIPTION, request.description)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(runIntent)
            } else {
                context.startService(runIntent)
            }
        } catch (error: Throwable) {
            pendingResults.remove(requestId, result)
            pendingIntent.cancel()
            throw error
        }
        return AndroidTermuxCommandHandle(requestId, result, pendingIntent)
    }

    private class AndroidTermuxCommandHandle(
        override val requestId: Int,
        private val result: CompletableDeferred<TermuxCommandResult>,
        private val pendingIntent: PendingIntent
    ) : TermuxCommandHandle {
        override suspend fun awaitResult(): TermuxCommandResult = result.await()

        override fun close() {
            pendingResults.remove(requestId, result)
            pendingIntent.cancel()
        }
    }

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
        private const val EXTRA_REQUEST_ID = "app.lynk.termux.REQUEST_ID"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val RUNNER_APP_SHELL = "app-shell"

        private val nextRequestId = AtomicInteger(1000)
        private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

        internal fun completeResult(intent: Intent?) {
            if (intent == null) return
            val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
            val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            pendingResults.remove(requestId)?.complete(TermuxCommandResult.from(resultBundle))
        }

        private fun mutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}

internal data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val termuxErrorCode: Int,
    val termuxErrorMessage: String
) {
    val succeeded: Boolean
        get() = (termuxErrorCode == Activity.RESULT_OK || termuxErrorCode == 0) && exitCode == 0

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
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"

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

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AndroidTermuxRunCommandGateway.completeResult(intent)
    }
}
