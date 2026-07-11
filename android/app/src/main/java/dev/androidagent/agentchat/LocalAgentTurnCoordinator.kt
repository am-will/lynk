package dev.androidagent.agentchat

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.localmodel.LiteRtLmRuntime
import dev.androidagent.localmodel.LocalAttachmentInputPreparer
import dev.androidagent.localmodel.LocalAgentController
import dev.androidagent.localmodel.LocalChatSessionStore
import dev.androidagent.localmodel.LocalModelRuntime
import dev.androidagent.localmodel.LocalToolRegistry
import dev.androidagent.localmodel.TermuxCommandCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class LocalAgentTurnCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    private val onStatus: (String, String) -> Unit,
    private val onChatMessage: (JSONObject) -> Unit,
    runtime: LocalModelRuntime = LiteRtLmRuntime(context.applicationContext)
) {
    private val store = LocalChatSessionStore(context.applicationContext)
    private val tools = LocalToolRegistry(context.applicationContext, commandExecutor, configProvider)
    private val controller = LocalAgentController(runtime, tools, configProvider, ::emit)
    private val localRuntime = runtime
    private var activeSessionKey: String = store.session(null).key
    private var activeRun: Job? = null
    private var activeToolOwner: String? = null

    fun open(sessionKey: String?): Boolean {
        activeSessionKey = store.session(sessionKey).key
        refresh(activeSessionKey, "Local phone model ready")
        return true
    }

    fun startTurn(request: LocalTurnRequest): Boolean {
        val trimmed = request.text.trim()
        if (trimmed.isBlank() && request.attachments.isEmpty()) return false
        val preparedAttachments = try {
            LocalAttachmentInputPreparer.prepare(trimmed, request.attachments)
        } catch (error: IllegalArgumentException) {
            emit(LocalChatMessages.error(activeSessionKey, error.message ?: "Local attachments are not supported."))
            return false
        }
        if (activeRun?.isActive == true) {
            emit(LocalChatMessages.error(activeSessionKey, request.busyMessage))
            return false
        }

        val session = store.append(
            request.sessionKey ?: activeSessionKey,
            "user",
            trimmed,
            attachments = request.attachments.map { it.preview() }
        )
        activeSessionKey = session.key
        if (request.emitUserMessageOnStart) {
            emit(LocalChatMessages.chatMessage(session.key, session.messages.last()))
        }

        val runId = "${request.runIdPrefix}_${UUID.randomUUID()}"
        val toolOwner = "local:${session.key}:$runId"
        activeToolOwner = toolOwner
        request.onAccepted(LocalTurnHandle(session.key, runId))
        activeRun = scope.launch {
            onStatus("Local model is working", "working")
            try {
                val finalText = controller.run(
                    sessionKey = session.key,
                    runId = runId,
                    userText = preparedAttachments.promptText,
                    history = session.messages.dropLast(1),
                    imagePaths = preparedAttachments.imagePaths
                )
                store.append(session.key, "assistant", finalText, "assistant_$runId")
                activeRun = null
                refresh(session.key, request.completedStatus)
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "completed", finalText))
                onStatus(request.completedStatus, "done")
                request.onCompleted(LocalTurnOutcome(session.key, runId, finalText))
            } catch (error: CancellationException) {
                val stoppedMessage = if (error is TermuxCommandCancellationException && !error.terminationVerified) {
                    "${request.stoppedMessage}. Termux process termination could not be verified; the command may still be running."
                } else {
                    request.stoppedMessage
                }
                activeRun = null
                emit(LocalChatMessages.error(session.key, stoppedMessage, runId))
                emit(LocalChatMessages.state(
                    config = configProvider(),
                    sessionKey = session.key,
                    runId = null,
                    isRunning = false,
                    status = stoppedMessage
                ))
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "failed", stoppedMessage))
                onStatus(stoppedMessage, "done")
                request.onCancelled(LocalTurnOutcome(session.key, runId, stoppedMessage))
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: error.toString()
                activeRun = null
                emit(LocalChatMessages.error(session.key, message, runId))
                emit(LocalChatMessages.state(
                    config = configProvider(),
                    sessionKey = session.key,
                    runId = null,
                    isRunning = false,
                    status = request.failedStatus
                ))
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "failed", message))
                onStatus(message, "error")
                request.onFailed(LocalTurnOutcome(session.key, runId, message))
            } finally {
                if (activeToolOwner == toolOwner) {
                    activeToolOwner = null
                }
            }
        }
        return true
    }

    fun stop(sessionKey: String? = null, reason: String = "Stopped local model turn") {
        val key = sessionKey ?: activeSessionKey
        activeToolOwner?.let(tools::cancelTermux)
        activeRun?.cancel()
        activeRun = null
        emit(LocalChatMessages.state(
            config = configProvider(),
            sessionKey = key,
            runId = null,
            isRunning = false,
            status = reason
        ))
        onStatus(reason, "done")
    }

    fun selectSession(sessionKey: String) {
        activeSessionKey = store.session(sessionKey).key
        refresh(activeSessionKey, "Switched local session")
    }

    fun newSession(label: String?) {
        activeToolOwner?.let(tools::cancelTermux)
        activeRun?.cancel()
        activeRun = null
        val session = store.create(label)
        activeSessionKey = session.key
        refresh(session.key, "Started a new local chat")
    }

    fun setModel(sessionKey: String?, model: String) {
        emit(LocalChatMessages.state(
            config = configProvider(),
            sessionKey = sessionKey ?: activeSessionKey,
            runId = null,
            isRunning = false,
            status = "Local mode uses the imported LiteRT-LM model",
            model = AgentModelOptions.LOCAL_LITERT_MODEL_ID
        ))
    }

    fun setReasoning(sessionKey: String?, reasoningEffort: String) {
        emit(LocalChatMessages.state(
            config = configProvider(),
            sessionKey = sessionKey ?: activeSessionKey,
            runId = null,
            isRunning = false,
            status = "Local reasoning set to $reasoningEffort",
            reasoningEffort = reasoningEffort
        ))
    }

    fun controlCommand(command: String) {
        when (command.trim().removePrefix("/").substringBefore(' ').lowercase()) {
            "status" -> refresh(activeSessionKey, "Local phone model mode is active")
            else -> emit(LocalChatMessages.error(activeSessionKey, "Local mode does not support /$command yet."))
        }
    }

    fun close() {
        tools.close()
        activeRun?.cancel()
        activeRun = null
        localRuntime.close()
    }

    private fun refresh(sessionKey: String, status: String) {
        val session = store.session(sessionKey)
        val config = configProvider()
        val toolDescriptions = tools.toolDescriptions()
        val toolDescriptionsJson = toolDescriptions.toString()
        emit(LocalChatMessages.models(config))
        emit(LocalChatMessages.tools(activeSessionKey, toolDescriptions))
        emit(LocalChatMessages.sessions(session.key, store.all(), config, toolDescriptionsJson))
        emit(LocalChatMessages.usage(session, config, toolDescriptionsJson))
        emit(LocalChatMessages.history(session))
        emit(LocalChatMessages.state(config, session.key, null, activeRun?.isActive == true, status))
    }

    private fun emit(message: JSONObject) {
        onChatMessage(message.put("deviceId", configProvider().deviceId))
    }

}

data class LocalTurnRequest(
    val text: String,
    val sessionKey: String? = null,
    val attachments: List<StoredChatAttachment> = emptyList(),
    val runIdPrefix: String = "local_run",
    val busyMessage: String = "A local turn is already running. Stop it before sending another request.",
    val stoppedMessage: String = "Stopped local model turn",
    val completedStatus: String = "Local model finished",
    val failedStatus: String = "Local model failed",
    val emitUserMessageOnStart: Boolean = false,
    val onAccepted: (LocalTurnHandle) -> Unit = {},
    val onCompleted: (LocalTurnOutcome) -> Unit = {},
    val onCancelled: (LocalTurnOutcome) -> Unit = {},
    val onFailed: (LocalTurnOutcome) -> Unit = {}
)

data class LocalTurnHandle(
    val sessionKey: String,
    val runId: String
)

data class LocalTurnOutcome(
    val sessionKey: String,
    val runId: String,
    val text: String
)
