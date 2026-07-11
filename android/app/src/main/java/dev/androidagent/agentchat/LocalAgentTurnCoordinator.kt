package dev.androidagent.agentchat

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.localmodel.LocalAttachmentInputPreparer
import dev.androidagent.localmodel.LocalAgentController
import dev.androidagent.localmodel.LocalChatSessionStore
import dev.androidagent.localmodel.LocalChatSessionRepository
import dev.androidagent.localmodel.LocalChatMessage
import dev.androidagent.localmodel.LocalModelRuntime
import dev.androidagent.localmodel.LocalPhoneCommandOwner
import dev.androidagent.localmodel.LocalToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class LocalAgentTurnCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val configProvider: () -> AgentConfig,
    private val onStatus: (String, String) -> Unit,
    private val onChatMessage: (JSONObject) -> Unit,
    private val store: LocalChatSessionRepository,
    private val toolDescriptions: () -> JSONArray,
    private val runner: LocalTurnRunner,
    private val cancelCommandOwner: (String) -> Unit = {}
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        commandExecutor: AccessibilityCommandExecutor,
        configProvider: () -> AgentConfig,
        onStatus: (String, String) -> Unit,
        onChatMessage: (JSONObject) -> Unit,
        runtime: LocalModelRuntime
    ) : this(
        scope = scope,
        configProvider = configProvider,
        onStatus = onStatus,
        onChatMessage = onChatMessage,
        dependencies = productionDependencies(context, commandExecutor, configProvider, onChatMessage, runtime)
    )

    private constructor(
        scope: CoroutineScope,
        configProvider: () -> AgentConfig,
        onStatus: (String, String) -> Unit,
        onChatMessage: (JSONObject) -> Unit,
        dependencies: LocalTurnDependencies
    ) : this(
        scope,
        configProvider,
        onStatus,
        onChatMessage,
        dependencies.store,
        dependencies.tools::toolDescriptions,
        dependencies.runner,
        dependencies.cancelCommandOwner
    )
    private var activeSessionKey: String = store.session(null).key
    private var generationSequence = 0L
    private var activeTurn: ActiveTurn? = null
    private var sessionTransition: Job? = null
    private var closed = false

    fun open(sessionKey: String?): Boolean {
        activeSessionKey = store.session(sessionKey).key
        refresh(activeSessionKey, "Local phone model ready")
        return true
    }

    fun startTurn(request: LocalTurnRequest): Boolean {
        if (closed) return false
        val trimmed = request.text.trim()
        if (trimmed.isBlank() && request.attachments.isEmpty()) return false
        val preparedAttachments = try {
            LocalAttachmentInputPreparer.prepare(trimmed, request.attachments)
        } catch (error: IllegalArgumentException) {
            emit(LocalChatMessages.error(activeSessionKey, error.message ?: "Local attachments are not supported."))
            return false
        }
        if (activeTurn?.job?.isActive == true || sessionTransition?.isActive == true) {
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
        val commandOwner = LocalPhoneCommandOwner.id(session.key, runId)
        request.onAccepted(LocalTurnHandle(session.key, runId))
        val generation = ++generationSequence
        val job = scope.launch(start = CoroutineStart.LAZY) {
            onStatus("Local model is working", "working")
            try {
                val finalText = runner.run(
                    sessionKey = session.key,
                    runId = runId,
                    userText = preparedAttachments.promptText,
                    history = session.messages.dropLast(1),
                    imagePaths = preparedAttachments.imagePaths
                )
                currentCoroutineContext().ensureActive()
                store.append(session.key, "assistant", finalText, "assistant_$runId")
                cancelCommandOwner(commandOwner)
                if (clearIfOwner(generation)) {
                    refresh(session.key, request.completedStatus)
                    onStatus(request.completedStatus, "done")
                }
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "completed", finalText))
                request.onCompleted(LocalTurnOutcome(session.key, runId, finalText))
            } catch (error: CancellationException) {
                cancelCommandOwner(commandOwner)
                val wasOwner = clearIfOwner(generation)
                emit(LocalChatMessages.error(session.key, request.stoppedMessage, runId))
                if (wasOwner) {
                    emitStoppedState(session.key, request.stoppedMessage)
                }
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "failed", request.stoppedMessage))
                request.onCancelled(LocalTurnOutcome(session.key, runId, request.stoppedMessage))
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: error.toString()
                cancelCommandOwner(commandOwner)
                val wasOwner = clearIfOwner(generation)
                emit(LocalChatMessages.error(session.key, message, runId))
                if (wasOwner) {
                    emit(LocalChatMessages.state(
                        config = configProvider(),
                        sessionKey = session.key,
                        runId = null,
                        isRunning = false,
                        status = request.failedStatus
                    ))
                    onStatus(message, "error")
                }
                emit(LocalChatMessages.replyAvailable(store.session(session.key), runId, "failed", message))
                request.onFailed(LocalTurnOutcome(session.key, runId, message))
            }
        }
        activeTurn = ActiveTurn(generation, session.key, runId, job)
        job.start()
        return true
    }

    fun stop(sessionKey: String? = null, reason: String = "Stopped local model turn") {
        val key = sessionKey ?: activeSessionKey
        detachAndCancelActive(reason)
        emitStoppedState(key, reason)
    }

    fun selectSession(sessionKey: String) {
        transitionSession("Switched local session") {
            store.session(sessionKey)
        }
    }

    fun newSession(label: String?) {
        transitionSession("Started a new local chat") {
            store.create(label)
        }
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
        if (closed) return
        closed = true
        sessionTransition?.cancel()
        sessionTransition = null
        detachAndCancelActive("Local chat route closed")
    }

    private fun refresh(sessionKey: String, status: String) {
        val session = store.session(sessionKey)
        val config = configProvider()
        val toolDescriptions = toolDescriptions()
        val toolDescriptionsJson = toolDescriptions.toString()
        emit(LocalChatMessages.models(config))
        emit(LocalChatMessages.tools(activeSessionKey, toolDescriptions))
        emit(LocalChatMessages.sessions(session.key, store.all(), config, toolDescriptionsJson))
        emit(LocalChatMessages.usage(session, config, toolDescriptionsJson))
        emit(LocalChatMessages.history(session))
        emit(LocalChatMessages.state(config, session.key, null, activeTurn?.job?.isActive == true, status))
    }

    private fun emit(message: JSONObject) {
        onChatMessage(message.put("deviceId", configProvider().deviceId))
    }

    private fun transitionSession(status: String, createSession: () -> dev.androidagent.localmodel.LocalChatSession) {
        if (closed) return
        sessionTransition?.cancel()
        val previous = detachAndCancelActive(status)
        lateinit var transition: Job
        transition = scope.launch(start = CoroutineStart.LAZY) {
            previous?.job?.join()
            currentCoroutineContext().ensureActive()
            if (sessionTransition !== transition || closed) return@launch
            val session = createSession()
            activeSessionKey = session.key
            refresh(session.key, status)
            if (sessionTransition === transition) {
                sessionTransition = null
            }
        }
        sessionTransition = transition
        transition.start()
    }

    private fun detachAndCancelActive(reason: String): ActiveTurn? {
        val turn = activeTurn ?: return null
        if (activeTurn?.generation == turn.generation) {
            activeTurn = null
        }
        cancelCommandOwner(LocalPhoneCommandOwner.id(turn.sessionKey, turn.runId))
        turn.job.cancel(CancellationException(reason))
        return turn
    }

    private fun clearIfOwner(generation: Long): Boolean {
        if (activeTurn?.generation != generation) return false
        activeTurn = null
        return true
    }

    private fun emitStoppedState(sessionKey: String, reason: String) {
        emit(LocalChatMessages.state(
            config = configProvider(),
            sessionKey = sessionKey,
            runId = null,
            isRunning = false,
            status = reason
        ))
        onStatus(reason, "done")
    }

    private data class ActiveTurn(
        val generation: Long,
        val sessionKey: String,
        val runId: String,
        val job: Job
    )

    companion object {
        private fun productionDependencies(
            context: Context,
            commandExecutor: AccessibilityCommandExecutor,
            configProvider: () -> AgentConfig,
            onChatMessage: (JSONObject) -> Unit,
            runtime: LocalModelRuntime
        ): LocalTurnDependencies {
            val appContext = context.applicationContext
            val tools = LocalToolRegistry(appContext, commandExecutor, configProvider)
            val emit: (JSONObject) -> Unit = { message ->
                onChatMessage(message.put("deviceId", configProvider().deviceId))
            }
            return LocalTurnDependencies(
                store = LocalChatSessionStore(appContext),
                tools = tools,
                runner = LocalAgentController(runtime, tools, configProvider, emit),
                cancelCommandOwner = tools::cancelApprovals
            )
        }
    }

}

interface LocalTurnRunner {
    suspend fun run(
        sessionKey: String,
        runId: String,
        userText: String,
        history: List<LocalChatMessage>,
        imagePaths: List<String> = emptyList()
    ): String
}

private data class LocalTurnDependencies(
    val store: LocalChatSessionRepository,
    val tools: LocalToolRegistry,
    val runner: LocalTurnRunner,
    val cancelCommandOwner: (String) -> Unit
)

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
