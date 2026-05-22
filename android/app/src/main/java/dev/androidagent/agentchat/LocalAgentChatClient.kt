package dev.androidagent.agentchat

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.localmodel.LiteRtLmRuntime
import dev.androidagent.localmodel.LocalAgentController
import dev.androidagent.localmodel.LocalChatSession
import dev.androidagent.localmodel.LocalChatSessionStore
import dev.androidagent.localmodel.LocalModelRuntime
import dev.androidagent.localmodel.LocalToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalAgentChatClient(
    context: Context,
    private val scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    private val onStatus: (String, String) -> Unit,
    private val onChatMessage: (JSONObject) -> Unit,
    runtime: LocalModelRuntime = LiteRtLmRuntime(context.applicationContext)
) : AgentChatClient {
    private val store = LocalChatSessionStore(context.applicationContext)
    private val tools = LocalToolRegistry(context.applicationContext, commandExecutor, configProvider)
    private val controller = LocalAgentController(runtime, tools, configProvider, ::emit)
    private val localRuntime = runtime
    private var activeSessionKey: String = store.session(null).key
    private var activeRun: Job? = null

    override fun open(sessionKey: String?): Boolean {
        activeSessionKey = store.session(sessionKey).key
        refresh(activeSessionKey, "Local phone model ready")
        return true
    }

    override fun send(text: String, sessionKey: String?, model: String?, reasoningEffort: String?): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (activeRun?.isActive == true) {
            emit(error(activeSessionKey, "A local turn is already running. Stop it before sending another request."))
            return false
        }
        val session = store.append(sessionKey ?: activeSessionKey, "user", trimmed)
        activeSessionKey = session.key
        val runId = "local_run_${UUID.randomUUID()}"
        activeRun = scope.launch {
            onStatus("Local model is working", "working")
            try {
                val finalText = controller.run(session.key, runId, trimmed, session.messages.dropLast(1))
                store.append(session.key, "assistant", finalText, "assistant_$runId")
                activeRun = null
                refresh(session.key, "Local model finished")
                emit(replyAvailable(session.key, runId, "completed", finalText))
                onStatus("Local model finished", "done")
            } catch (error: kotlinx.coroutines.CancellationException) {
                val message = "Stopped local model turn"
                activeRun = null
                emit(error(session.key, message, runId))
                emit(state(session.key, null, isRunning = false, status = message))
                emit(replyAvailable(session.key, runId, "failed", message))
                onStatus(message, "done")
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: error.toString()
                activeRun = null
                emit(error(session.key, message, runId))
                emit(state(session.key, null, isRunning = false, status = "Local model failed"))
                emit(replyAvailable(session.key, runId, "failed", message))
                onStatus(message, "error")
            }
        }
        return true
    }

    override fun stop(sessionKey: String?, runId: String?, reason: String) {
        val key = sessionKey ?: activeSessionKey
        activeRun?.cancel()
        activeRun = null
        emit(state(key, null, isRunning = false, status = reason))
        onStatus(reason, "done")
    }

    override fun selectSession(sessionKey: String) {
        activeSessionKey = store.session(sessionKey).key
        refresh(activeSessionKey, "Switched local session")
    }

    override fun newSession(label: String?, model: String?) {
        activeRun?.cancel()
        activeRun = null
        val session = store.create(label)
        activeSessionKey = session.key
        refresh(session.key, "Started a new local chat")
    }

    override fun setModel(sessionKey: String?, model: String) {
        emit(state(sessionKey ?: activeSessionKey, null, false, "Local mode uses the imported LiteRT-LM model", model = AgentModelOptions.LOCAL_LITERT_MODEL_ID))
    }

    override fun setReasoning(sessionKey: String?, reasoningEffort: String) {
        emit(state(sessionKey ?: activeSessionKey, null, false, "Local reasoning set to $reasoningEffort", reasoningEffort = reasoningEffort))
    }

    override fun controlCommand(command: String, args: JSONObject) {
        when (command.trim().removePrefix("/").substringBefore(' ').lowercase()) {
            "status" -> refresh(activeSessionKey, "Local phone model mode is active")
            else -> emit(error(activeSessionKey, "Local mode does not support /$command yet."))
        }
    }

    override fun close() {
        activeRun?.cancel()
        activeRun = null
        localRuntime.close()
    }

    private fun refresh(sessionKey: String, status: String) {
        val session = store.session(sessionKey)
        emit(models())
        emit(tools())
        emit(sessions(session.key))
        emit(history(session))
        emit(state(session.key, null, activeRun?.isActive == true, status))
    }

    private fun emit(message: JSONObject) {
        onChatMessage(message.put("deviceId", configProvider().deviceId))
    }

    private fun models(): JSONObject =
        JSONObject()
            .put("type", "chat.models")
            .put("source", "local")
            .put("models", JSONArray().put(JSONObject()
                .put("id", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                .put("label", "Local LiteRT-LM")
                .put("provider", "android")
                .put("available", true)))
            .put("reasoningOptions", JSONArray()
                .put(JSONObject().put("id", "low").put("label", "low"))
                .put(JSONObject().put("id", "medium").put("label", "medium"))
                .put(JSONObject().put("id", "high").put("label", "high")))

    private fun tools(): JSONObject =
        JSONObject()
            .put("type", "chat.tools")
            .put("sessionKey", activeSessionKey)
            .put("tools", tools.toolDescriptions())

    private fun sessions(selectedKey: String): JSONObject =
        JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", selectedKey)
            .put("sessions", JSONArray().also { array ->
                store.all().forEach { session ->
                    array.put(JSONObject()
                        .put("key", session.key)
                        .put("label", session.label)
                        .put("displayName", session.label)
                        .put("updatedAt", session.updatedAt)
                        .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                        .put("modelProvider", "android")
                        .put("thinkingLevel", configProvider().reasoningEffort))
                }
            })

    private fun history(session: LocalChatSession): JSONObject =
        JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", session.key)
            .put("messages", JSONArray().also { array ->
                session.messages.forEach { message ->
                    array.put(JSONObject()
                        .put("id", message.id)
                        .put("role", message.role)
                        .put("text", message.text)
                        .put("timestamp", message.timestamp))
                }
            })

    private fun state(
        sessionKey: String,
        runId: String?,
        isRunning: Boolean,
        status: String,
        model: String = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
        reasoningEffort: String = configProvider().reasoningEffort
    ): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("model", model)
            .put("reasoningEffort", reasoningEffort)

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
            .put("textPreview", text.trim().take(180))
            .put("sessionLabel", session.label)
            .put("sessionDisplayName", session.label)
    }

}
