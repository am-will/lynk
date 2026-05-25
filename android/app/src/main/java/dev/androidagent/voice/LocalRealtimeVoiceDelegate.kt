package dev.androidagent.voice

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.localmodel.LiteRtLmRuntime
import dev.androidagent.localmodel.LocalAgentController
import dev.androidagent.localmodel.LocalChatSessionStore
import dev.androidagent.localmodel.LocalModelRuntime
import dev.androidagent.localmodel.LocalResponseTextNormalizer
import dev.androidagent.localmodel.LocalToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class LocalRealtimeVoiceDelegate(
    context: Context,
    private val scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    private val onStatus: (String, String) -> Unit,
    private val onChatMessage: (JSONObject) -> Unit,
    private val onRealtimeToolResult: (JSONObject) -> Unit,
    private val onRealtimeTaskStatus: (JSONObject) -> Unit,
    runtime: LocalModelRuntime = LiteRtLmRuntime(context.applicationContext)
) {
    private val store = LocalChatSessionStore(context.applicationContext)
    private val tools = LocalToolRegistry(context.applicationContext, commandExecutor, configProvider)
    private val controller = LocalAgentController(runtime, tools, configProvider, ::emitChat)
    private val localRuntime = runtime
    private val queue = ArrayDeque<QueuedLocalRealtimeTask>()
    private var activeTask: QueuedLocalRealtimeTask? = null
    private var activeJob: Job? = null
    private var activeSessionKey: String = store.session(null).key
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
        activeJob?.cancel()
        activeJob = null
        activeTask = null
        queue.clear()
        localRuntime.close()
    }

    private fun startTask(task: QueuedLocalRealtimeTask) {
        activeTask = task
        val session = store.append(activeSessionKey, "user", task.instruction)
        activeSessionKey = session.key
        emitChat(message(session.key, session.messages.last()))
        val runId = "local_realtime_${UUID.randomUUID()}"
        sendTaskStatus()
        activeJob = scope.launch {
            onStatus("Local model is working", "working")
            try {
                val finalText = controller.run(
                    sessionKey = session.key,
                    runId = runId,
                    userText = task.instruction,
                    history = session.messages.dropLast(1)
                )
                store.append(session.key, "assistant", finalText, "assistant_$runId")
                completed += 1
                emitChat(state(session.key, null, isRunning = false, status = "Local model finished"))
                emitChat(replyAvailable(session.key, runId, "completed", finalText))
                onStatus("Local model finished", "done")
                sendResult(task.callId, ok = true, status = "completed", output = finalText)
            } catch (error: CancellationException) {
                failed += 1
                sendResult(task.callId, ok = false, status = "cancelled", error = "Stopped local realtime task.")
                onStatus("Stopped local realtime task", "done")
            } catch (error: Throwable) {
                val message = error.message ?: error.toString()
                failed += 1
                emitChat(error(session.key, message, runId))
                emitChat(state(session.key, null, isRunning = false, status = "Local model failed"))
                emitChat(replyAvailable(session.key, runId, "failed", message))
                onStatus(message, "error")
                sendResult(task.callId, ok = false, status = "failed", error = message)
            } finally {
                if (activeTask?.callId == task.callId) {
                    activeTask = null
                    activeJob = null
                }
                sendTaskStatus()
                startNext()
            }
        }
    }

    private fun stopActive(call: RealtimeToolCall) {
        val reason = call.arguments.optString("reason").ifBlank { "Stopped by realtime voice" }
        val queuedTasks = generateSequence { queue.poll() }.toList()
        queuedTasks.forEach { task ->
            failed += 1
            sendResult(task.callId, ok = false, status = "cancelled", error = reason)
        }
        activeJob?.cancel()
        activeJob = null
        activeTask = null
        sendResult(
            callId = call.callId,
            ok = true,
            status = "completed",
            output = "Stopped the active local task and cleared queued realtime tasks.",
            createResponse = false
        )
        sendTaskStatus()
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

    private fun emitChat(message: JSONObject) {
        onChatMessage(message.put("deviceId", configProvider().deviceId))
    }

    private fun message(sessionKey: String, message: dev.androidagent.localmodel.LocalChatMessage): JSONObject =
        JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", sessionKey)
            .put("message", JSONObject()
                .put("id", message.id)
                .put("role", message.role)
                .put("text", message.text)
                .put("timestamp", message.timestamp))

    private fun state(sessionKey: String, runId: String?, isRunning: Boolean, status: String): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
            .put("reasoningEffort", configProvider().reasoningEffort)

    private fun error(sessionKey: String, message: String, runId: String? = null): JSONObject =
        JSONObject()
            .put("type", "chat.error")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("message", message)

    private fun replyAvailable(sessionKey: String, runId: String, status: String, text: String): JSONObject {
        val session = store.session(sessionKey)
        return JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("status", status)
            .put("textPreview", LocalResponseTextNormalizer.normalize(text).take(180))
            .put("sessionLabel", session.label)
            .put("sessionDisplayName", session.label)
    }

    private data class QueuedLocalRealtimeTask(
        val callId: String,
        val instruction: String
    )
}
