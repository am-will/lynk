package dev.androidagent.voice

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.agentchat.LocalAgentTurnCoordinator
import dev.androidagent.agentchat.LocalTurnRequest
import dev.androidagent.localmodel.LocalModelEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayDeque

class LocalRealtimeVoiceDelegate(
    context: Context,
    private val scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    onStatus: (String, String) -> Unit,
    onChatMessage: (JSONObject) -> Unit,
    private val onRealtimeToolResult: (JSONObject) -> Unit,
    private val onRealtimeTaskStatus: (JSONObject) -> Unit,
    engineManager: LocalModelEngineManager
) {
    private val coordinator = LocalAgentTurnCoordinator(
        context = context,
        scope = scope,
        commandExecutor = commandExecutor,
        configProvider = configProvider,
        onStatus = onStatus,
        onChatMessage = onChatMessage,
        runtime = engineManager
    )
    private val queue = ArrayDeque<QueuedLocalRealtimeTask>()
    private val acceptedCallIds = LinkedHashSet<String>()
    private var activeTask: QueuedLocalRealtimeTask? = null
    private var generation = 0L
    private var closed = false
    private var completed = 0
    private var failed = 0

    fun handleToolCall(call: RealtimeToolCall) {
        if (closed) return
        if (!acceptedCallIds.add(call.callId)) return
        while (acceptedCallIds.size > MAX_TRACKED_CALL_IDS) {
            acceptedCallIds.remove(acceptedCallIds.first())
        }
        when (val intent = RealtimeToolRouting.intentFor(call.name, call.arguments)) {
            is RealtimeToolIntent.StartTask -> enqueueTask(call.callId, call.name, intent.instruction)
            is RealtimeToolIntent.SteerTask -> handleSteer(call, intent)
            is RealtimeToolIntent.StopTask -> stopActive(call, intent.reason)
            RealtimeToolIntent.BridgeOnly -> failCall(call.callId, "${call.name} must be handled by the bridge realtime path.")
            is RealtimeToolIntent.Unsupported -> failCall(call.callId, "Unsupported realtime tool ${intent.toolName}.")
        }
    }

    fun close(): Job {
        closed = true
        generation += 1
        coordinator.close()
        activeTask = null
        queue.clear()
        sendTaskStatus()
        return scope.launch { coordinator.closeAndJoin() }
    }

    fun stopAndJoin(reason: String): Job {
        generation += 1
        val cancelled = buildList {
            activeTask?.let(::add)
            addAll(generateSequence { queue.poll() }.toList())
        }
        activeTask = null
        cancelled.forEach { task ->
            failed += 1
            sendResult(task.callId, ok = false, status = "cancelled", error = reason)
        }
        sendTaskStatus()
        return scope.launch { coordinator.stopAndJoin(reason = reason) }
    }

    private fun enqueueTask(callId: String, toolName: String, instruction: String) {
        if (instruction.isBlank()) {
            failCall(callId, "$toolName requires a non-empty instruction.")
            return
        }
        val task = QueuedLocalRealtimeTask(callId = callId, instruction = instruction)
        if (activeTask != null) {
            if (queue.size >= MAX_QUEUED_TASKS) {
                failCall(callId, "Local realtime task queue is full ($MAX_QUEUED_TASKS).")
                return
            }
            queue.add(task)
            sendTaskStatus()
            return
        }
        startTask(task)
    }

    private fun handleSteer(call: RealtimeToolCall, intent: RealtimeToolIntent.SteerTask) {
        if (intent.guidance.isBlank()) {
            failCall(call.callId, "${call.name} requires non-empty guidance.")
            return
        }
        if (activeTask != null) {
            failCall(call.callId, "Local LiteRT-LM cannot steer an active realtime task yet. Stop it or wait for it to finish.")
            return
        }
        startTask(QueuedLocalRealtimeTask(callId = call.callId, instruction = intent.guidance))
    }

    private fun startTask(task: QueuedLocalRealtimeTask) {
        val taskGeneration = generation
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
                    if (taskGeneration != generation || closed) return@LocalTurnRequest
                    completed += 1
                    sendResult(task.callId, ok = true, status = "completed", output = outcome.text)
                    finishTask(task)
                },
                onCancelled = { outcome ->
                    if (taskGeneration != generation || closed) return@LocalTurnRequest
                    failed += 1
                    sendResult(task.callId, ok = false, status = "cancelled", error = outcome.text)
                    finishTask(task)
                },
                onFailed = { outcome ->
                    if (taskGeneration != generation || closed) return@LocalTurnRequest
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

    private fun stopActive(call: RealtimeToolCall, reason: String) {
        stopAndJoin(reason)
        sendResult(
            callId = call.callId,
            ok = true,
            status = "completed",
            output = "Stopped the active local task and cleared queued realtime tasks.",
            createResponse = false
        )
        sendTaskStatus()
    }

    private fun failCall(callId: String, error: String) {
        failed += 1
        sendResult(callId, ok = false, status = "failed", error = error)
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

    companion object {
        internal const val MAX_QUEUED_TASKS = 8
        private const val MAX_TRACKED_CALL_IDS = 128
    }
}
