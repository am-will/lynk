package dev.androidagent.voice

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.agentchat.LocalAgentTurnCoordinator
import dev.androidagent.agentchat.LocalTurnRequest
import dev.androidagent.localmodel.LiteRtLmRuntime
import dev.androidagent.localmodel.LocalModelRuntime
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.ArrayDeque

class LocalRealtimeVoiceDelegate(
    context: Context,
    scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    onStatus: (String, String) -> Unit,
    onChatMessage: (JSONObject) -> Unit,
    private val onRealtimeToolResult: (JSONObject) -> Unit,
    private val onRealtimeTaskStatus: (JSONObject) -> Unit,
    runtime: LocalModelRuntime = LiteRtLmRuntime(context.applicationContext)
) {
    private val coordinator = LocalAgentTurnCoordinator(
        context = context,
        scope = scope,
        commandExecutor = commandExecutor,
        configProvider = configProvider,
        onStatus = onStatus,
        onChatMessage = onChatMessage,
        runtime = runtime
    )
    private val queue = ArrayDeque<QueuedLocalRealtimeTask>()
    private var activeTask: QueuedLocalRealtimeTask? = null
    private var completed = 0
    private var failed = 0

    fun handleToolCall(call: RealtimeToolCall) {
        if (RealtimeToolRouting.isStopTool(call.name)) {
            stopActive(call)
            return
        }

        val instruction = RealtimeToolRouting.instruction(call.arguments)
        if (instruction.isBlank()) {
            failed += 1
            sendResult(call.callId, ok = false, status = "failed", error = "${call.name} requires a non-empty instruction.")
            sendTaskStatus()
            return
        }

        val task = QueuedLocalRealtimeTask(callId = call.callId, instruction = instruction)
        if (activeTask != null) {
            queue.add(task)
            sendTaskStatus()
            return
        }
        startTask(task)
    }

    fun close() {
        coordinator.close()
        activeTask = null
        queue.clear()
    }

    private fun startTask(task: QueuedLocalRealtimeTask) {
        activeTask = task
        sendTaskStatus()
        val started = coordinator.startTurn(
            LocalTurnRequest(
                text = task.instruction,
                runIdPrefix = "local_realtime",
                stoppedMessage = "Stopped local realtime task",
                completedStatus = "Local model finished",
                failedStatus = "Local model failed",
                emitUserMessageOnStart = true,
                onCompleted = { outcome ->
                    completed += 1
                    sendResult(task.callId, ok = true, status = "completed", output = outcome.text)
                    finishTask(task)
                },
                onCancelled = { outcome ->
                    failed += 1
                    sendResult(task.callId, ok = false, status = "cancelled", error = outcome.text)
                    finishTask(task)
                },
                onFailed = { outcome ->
                    failed += 1
                    sendResult(task.callId, ok = false, status = "failed", error = outcome.text)
                    finishTask(task)
                }
            )
        )
        if (!started) {
            failed += 1
            sendResult(task.callId, ok = false, status = "failed", error = "Could not start local realtime task.")
            finishTask(task)
        }
    }

    private fun stopActive(call: RealtimeToolCall) {
        val reason = call.arguments.optString("reason").ifBlank { "Stopped by realtime voice" }
        val queuedTasks = generateSequence { queue.poll() }.toList()
        queuedTasks.forEach { task ->
            failed += 1
            sendResult(task.callId, ok = false, status = "cancelled", error = reason)
        }
        if (activeTask != null) {
            coordinator.stop(reason = reason)
        }
        sendResult(
            callId = call.callId,
            ok = true,
            status = "completed",
            output = "Stopped the active local task and cleared queued realtime tasks.",
            createResponse = false
        )
        sendTaskStatus()
    }

    private fun finishTask(task: QueuedLocalRealtimeTask) {
        if (activeTask?.callId == task.callId) {
            activeTask = null
        }
        sendTaskStatus()
        startNext()
    }

    private fun startNext() {
        val next = queue.poll() ?: return
        startTask(next)
    }

    private fun sendResult(
        callId: String,
        ok: Boolean,
        status: String,
        output: String? = null,
        error: String? = null,
        createResponse: Boolean = true
    ) {
        onRealtimeToolResult(JSONObject()
            .put("type", "realtime.tool_result")
            .put("deviceId", configProvider().deviceId)
            .put("callId", callId)
            .put("ok", ok)
            .put("status", status)
            .put("createResponse", createResponse)
            .also { payload ->
                output?.takeIf { it.isNotBlank() }?.let { payload.put("output", it) }
                error?.takeIf { it.isNotBlank() }?.let { payload.put("error", it) }
            })
    }

    private fun sendTaskStatus() {
        onRealtimeTaskStatus(JSONObject()
            .put("type", "realtime.task_status")
            .put("deviceId", configProvider().deviceId)
            .put("running", activeTask != null)
            .put("queued", queue.size)
            .put("currentTask", activeTask?.instruction ?: JSONObject.NULL)
            .put("completed", completed)
            .put("failed", failed))
    }

    private data class QueuedLocalRealtimeTask(
        val callId: String,
        val instruction: String
    )
}
