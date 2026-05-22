package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.agentchat.AgentChatClient
import dev.androidagent.agentchat.HostAgentChatClient
import dev.androidagent.agentchat.LocalAgentChatClient
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatStateReducer
import dev.androidagent.chat.ChatTimelineItem
import dev.androidagent.chat.ChatTimelineKind
import dev.androidagent.chat.ChatUnreadReply
import dev.androidagent.chat.ChatUsageSummary
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.net.BridgeConnectionPhase
import dev.androidagent.net.BridgeConnectionState
import dev.androidagent.net.PhoneWebSocketClient
import dev.androidagent.overlay.HostConnectionPhase
import dev.androidagent.overlay.HostConnectionState
import dev.androidagent.overlay.PanelPresentation
import dev.androidagent.overlay.ChatPresentationHelpers
import dev.androidagent.voice.VoiceRuntimeController
import dev.androidagent.voice.transcription.VoiceTranscriptionManager
import dev.androidagent.voice.transcription.VoiceTranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private enum class ChatClientRoute {
    Host,
    Local
}

class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlayController: OverlayController? = null
    private var webSocketClient: PhoneWebSocketClient? = null
    private var chatClient: AgentChatClient? = null
    private var chatClientRoute: ChatClientRoute? = null
    private var commandExecutor: AccessibilityCommandExecutor? = null
    private var voiceRuntimeController: VoiceRuntimeController? = null
    private var voiceTranscriptionManager: VoiceTranscriptionManager? = null
    private var lastNotificationText = DEFAULT_NOTIFICATION_TEXT
    private var isAgentTurnActive = false
    private var chatState = ChatState()
    private var pendingNewChat = false
    private var notifiedReplySessions = emptySet<String>()
    private val closeSystemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            if (intent.getStringExtra(SYSTEM_DIALOG_REASON) == SYSTEM_DIALOG_REASON_HOME_KEY) {
                overlayController?.minimizePanelFromSystemHome()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        broadcastRunningState()
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = false))
        AgentConfigStore.load(this).also { config ->
            chatState = chatState.copy(selectedModel = selectedChatModel(config))
            AvatarLibrary.scanOnBoot(applicationContext, config.hostUrl, config.token)
        }
        voiceTranscriptionManager = VoiceTranscriptionManager(onStateChanged = ::handleTranscriptionState)
        overlayController = OverlayController(
            context = this,
            onSubmit = { text -> submitChatText(text) },
            onStop = { requestStopTurn("Stopped from Android overlay") },
            onDismiss = { stopSelf() },
            onStartVoice = {
                val config = AgentConfigStore.load(this)
                if (selectedChatModel(config) == AgentModelOptions.LOCAL_LITERT_MODEL_ID) {
                    overlayController?.setStatus("Realtime voice still requires a host model.")
                } else {
                    connectAgentClient(selectedChatModel(config))
                    promoteVoiceForegroundIfAllowed()
                    voiceRuntimeController?.start()
                }
            },
            onToggleVoiceMute = { voiceRuntimeController?.toggleMute() },
            onStopVoice = { voiceRuntimeController?.stopFromUi() },
            onStartTranscription = { startComposerTranscription() },
            onStopTranscription = { stopComposerTranscription() },
            onCancelTranscription = { cancelComposerTranscription() },
            onSelectChatSession = { sessionKey ->
                val route = routeForSessionKey(sessionKey)
                connectAgentClient(routeOverride = route).selectSession(sessionKey)
                markChatSessionRead(sessionKey)
            },
            onNewChatSession = { startNewChatFromUi() },
            onSetChatModel = { model ->
                setChatModelFromUi(model)
            },
            onSetChatReasoning = { reasoning ->
                val config = AgentConfigStore.load(this)
                val model = selectedChatModel(config)
                val route = routeForModel(model, config)
                connectAgentClient(model).setReasoning(sessionKeyForRoute(route), reasoning)
            },
            onChatControlCommand = { command, args -> submitChatControlCommand(command, args) },
            onToggleChatTool = { eventId ->
                chatState = ChatStateReducer.toggleTool(chatState, eventId)
                overlayController?.setChatState(chatState)
            },
            onChatSessionViewed = { sessionKey -> markChatSessionRead(sessionKey) }
        ).also {
            it.setChatState(chatState)
            it.show()
        }
        voiceRuntimeController = VoiceRuntimeController(
            context = this,
            sendStart = { sdp, config -> webSocketClient?.sendRealtimeStart(sdp, config, AgentLocationProvider.currentBestEffortLocation(this)) },
            sendStop = { reason ->
                webSocketClient?.sendRealtimeStop(reason)
            },
            sendToolCall = { call -> webSocketClient?.sendRealtimeToolCall(call, AgentConfigStore.load(this)) },
            onStateChanged = { state -> overlayController?.setVoiceState(state) }
        )
        registerCloseSystemDialogsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TURN -> {
                requestStopTurn("Stopped from Android notification")
                return START_STICKY
            }
            ACTION_OPEN_CHAT -> {
                openChatFromIntent(intent)
                return START_STICKY
            }
            ACTION_OPEN_CHAT_SESSION -> {
                val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY)
                if (!sessionKey.isNullOrBlank()) {
                    openChatSessionFromNotification(sessionKey, panelPresentationFrom(intent))
                } else {
                    openChatFromIntent(intent)
                }
                return START_STICKY
            }
            ACTION_REFRESH_AVATAR -> {
                overlayController?.refreshBubbleAvatar()
                return START_STICKY
            }
            ACTION_RESIZE_BUBBLE -> {
                val size = intent.getIntExtra(EXTRA_BUBBLE_SIZE_DP, AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP)
                overlayController?.refreshBubbleSize(size)
                return START_STICKY
            }
        }
        overlayController?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        voiceRuntimeController?.stopFromUi()
        voiceRuntimeController?.close()
        voiceTranscriptionManager?.close()
        serviceScope.cancel()
        chatClient?.close()
        webSocketClient?.close()
        unregisterCloseSystemDialogsReceiver()
        overlayController?.hide()
        isRunning = false
        broadcastRunningState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectAgentClient(
        modelOverride: String? = null,
        routeOverride: ChatClientRoute? = null
    ): AgentChatClient {
        val config = AgentConfigStore.load(this)
        val route = routeOverride ?: routeForModel(modelOverride ?: selectedChatModel(config), config)
        val currentClient = chatClient
        if (currentClient != null && chatClientRoute == route) {
            return currentClient
        }
        chatClientRoute = route
        currentClient?.close()
        val nextClient = when (route) {
            ChatClientRoute.Host -> HostAgentChatClient(connectWebSocket(config))
            ChatClientRoute.Local -> {
                webSocketClient?.close()
                webSocketClient = null
                handleBridgeConnectionState(BridgeConnectionState(BridgeConnectionPhase.CONNECTED, "Local phone model mode"))
                LocalAgentChatClient(
                    context = this,
                    scope = serviceScope,
                    commandExecutor = commandExecutor(),
                    configProvider = { AgentConfigStore.load(this) },
                    onStatus = ::handleBridgeStatus,
                    onChatMessage = { handleChatMessage(it) }
                ).also { it.open(sessionKeyForRoute(ChatClientRoute.Local)) }
            }
        }
        chatClient = nextClient
        return nextClient
    }

    private fun connectWebSocket(config: AgentConfig = AgentConfigStore.load(this)): PhoneWebSocketClient {
        webSocketClient?.let { return it }
        val executor = commandExecutor()
        return PhoneWebSocketClient(
            config = config,
            commandExecutor = executor,
            onStatus = { text, status ->
                if (chatClientRoute == ChatClientRoute.Host) {
                    handleBridgeStatus(text, status)
                }
            },
            onConnectionState = { state ->
                if (chatClientRoute == ChatClientRoute.Host) {
                    handleBridgeConnectionState(state)
                }
            },
            onRealtimeSdp = { voiceRuntimeController?.onRealtimeSdp(it) },
            onRealtimeTranscriptDelta = { voiceRuntimeController?.onRealtimeTranscriptDelta(it) },
            onRealtimeItemAdded = { voiceRuntimeController?.onRealtimeItemAdded(it) },
            onRealtimeSpeechStarted = { voiceRuntimeController?.onRealtimeSpeechStarted(it) },
            onRealtimeError = { voiceRuntimeController?.onRealtimeError(it) },
            onRealtimeClosed = { voiceRuntimeController?.onRealtimeClosed(it) },
            onRealtimeToolResult = { voiceRuntimeController?.onRealtimeToolResult(it) },
            onRealtimeTaskStatus = { voiceRuntimeController?.onRealtimeTaskStatus(it) },
            onChatMessage = {
                if (chatClientRoute == ChatClientRoute.Host) {
                    handleChatMessage(it)
                }
            }
        ).also {
            webSocketClient = it
            it.connect()
        }
    }

    private fun selectedChatModel(config: AgentConfig = AgentConfigStore.load(this)): String {
        val selected = chatState.selectedModel?.takeIf { it.isNotBlank() }
        val fallback = config.model.takeUnless { it == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
            ?: AgentModelOptions.models.first().id
        return when (selected) {
            AgentModelOptions.LOCAL_LITERT_MODEL_ID -> {
                if (isExperimentalLocalModelAvailable(config)) selected else fallback
            }
            null -> fallback
            else -> selected
        }
    }

    private fun routeForModel(model: String, config: AgentConfig = AgentConfigStore.load(this)): ChatClientRoute {
        return if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID && isExperimentalLocalModelAvailable(config)) {
            ChatClientRoute.Local
        } else {
            ChatClientRoute.Host
        }
    }

    private fun isExperimentalLocalModelAvailable(config: AgentConfig = AgentConfigStore.load(this)): Boolean =
        config.experimentalLocalModelsEnabled && LocalModelStore.exists(config.localModelPath)

    private fun modelForRoute(model: String, route: ChatClientRoute, config: AgentConfig): String {
        return when (route) {
            ChatClientRoute.Local -> AgentModelOptions.LOCAL_LITERT_MODEL_ID
            ChatClientRoute.Host -> model
                .takeUnless { it == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?: config.model.takeUnless { it == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?: AgentModelOptions.models.first().id
        }
    }

    private fun sessionKeyForRoute(route: ChatClientRoute): String? {
        val key = chatState.sessionKey?.takeIf { it.isNotBlank() } ?: return null
        return when (route) {
            ChatClientRoute.Local -> key.takeIf { it.startsWith("local:") }
            ChatClientRoute.Host -> key.takeUnless { it.startsWith("local:") }
        }
    }

    private fun routeForSessionKey(
        sessionKey: String,
        config: AgentConfig = AgentConfigStore.load(this)
    ): ChatClientRoute {
        return if (sessionKey.startsWith("local:") && isExperimentalLocalModelAvailable(config)) {
            ChatClientRoute.Local
        } else {
            ChatClientRoute.Host
        }
    }

    private fun activeChatRoute(config: AgentConfig = AgentConfigStore.load(this)): ChatClientRoute {
        chatState.sessionKey?.takeIf { it.isNotBlank() }?.let { sessionKey ->
            return routeForSessionKey(sessionKey, config)
        }
        return routeForModel(selectedChatModel(config), config)
    }

    private fun commandExecutor(): AccessibilityCommandExecutor {
        return commandExecutor ?: AccessibilityCommandExecutor(this, overlayController).also {
            commandExecutor = it
        }
    }

    private fun registerCloseSystemDialogsReceiver() {
        ContextCompat.registerReceiver(
            this,
            closeSystemDialogsReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterCloseSystemDialogsReceiver() {
        runCatching { unregisterReceiver(closeSystemDialogsReceiver) }
    }

    private fun submitChatText(text: String): Boolean {
        if (text.trimStart().startsWith("/")) {
            return submitSlashCommand(text)
        }
        chatState = ChatStateReducer.localUserMessage(chatState, text)
        overlayController?.setChatState(chatState)
        val requestConfig = AgentConfigStore.load(this)
        val selectedModel = selectedChatModel(requestConfig)
        val route = routeForModel(selectedModel, requestConfig)
        val client = connectAgentClient(selectedModel)
        val sent = client.send(
            text = text,
            sessionKey = sessionKeyForRoute(route),
            model = modelForRoute(selectedModel, route, requestConfig),
            reasoningEffort = chatState.reasoningEffort ?: requestConfig.reasoningEffort
        )
        if (sent) {
            lastNotificationText = if (route == ChatClientRoute.Local) "Sent to local model" else "Sent to OpenClaw"
            isAgentTurnActive = true
        } else {
            lastNotificationText = if (route == ChatClientRoute.Local) "Local model is not ready" else "Bridge is not connected"
            isAgentTurnActive = false
        }
        updateNotification()
        return sent
    }

    private fun setChatModelFromUi(model: String) {
        val config = AgentConfigStore.load(this)
        if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !isExperimentalLocalModelAvailable(config)) {
            overlayController?.setStatus("Enable Experimental and import a LiteRT model first.")
            return
        }
        val route = routeForModel(model, config)
        AgentConfigStore.save(this, config.copy(model = model))
        chatState = chatState.copy(
            selectedModel = model,
            status = "Model: ${chatModelDisplayLabel(model, route)}",
            error = null
        )
        overlayController?.setChatState(chatState)
        connectAgentClient(model).setModel(sessionKeyForRoute(route), modelForRoute(model, route, config))
        lastNotificationText = chatState.status ?: lastNotificationText
        updateNotification()
    }

    private fun chatModelDisplayLabel(model: String, route: ChatClientRoute): String {
        if (route == ChatClientRoute.Local) return "Local LiteRT-LM"
        val option = chatState.models.firstOrNull { it.id == model }
        val harness = option?.let { ChatPresentationHelpers.modelHarnessLabel(it) }
        val label = option?.label ?: AgentModelOptions.modelLabel(model)
        return harness?.let { "$it / $label" } ?: label
    }

    private fun submitSlashCommand(text: String): Boolean {
        val slashText = text.trim()
        val command = slashText.removePrefix("/").substringBefore(' ').trim().lowercase()
        if (command.isBlank()) {
            return false
        }
        if (command == "new") {
            startNewChatFromUi()
            return true
        }

        chatState = ChatStateReducer.localUserMessage(chatState, slashText).copy(
            status = "Running $slashText"
        )
        overlayController?.setChatState(chatState)
        connectAgentClient(routeOverride = activeChatRoute()).controlCommand(slashText, JSONObject())
        lastNotificationText = "Running $slashText"
        isAgentTurnActive = command != "status"
        updateNotification()
        return true
    }

    private fun submitChatControlCommand(command: String, args: JSONObject) {
        val notice = ChatStateReducer.localControlNotice(command, args)
        if (!notice.isNullOrBlank()) {
            chatState = ChatStateReducer.localControlCommand(chatState, command, args)
            overlayController?.setChatState(chatState)
            lastNotificationText = notice
            updateNotification()
        }
        connectAgentClient(routeOverride = activeChatRoute()).controlCommand(command, args)
    }

    private fun startNewChatFromUi() {
        pendingNewChat = true
        val now = System.currentTimeMillis()
        chatState = chatState.copy(
            sessionKey = null,
            sessionId = null,
            activeRunId = null,
            isRunning = false,
            status = "Started a new chat",
            error = null,
            timeline = listOf(ChatTimelineItem(
                id = "system_${UUID.randomUUID()}",
                kind = ChatTimelineKind.MESSAGE,
                role = "system",
                text = "Started a new chat",
                timestamp = now
            )),
            usage = ChatUsageSummary()
        )
        overlayController?.setChatState(chatState)
        val config = AgentConfigStore.load(this)
        val selectedModel = selectedChatModel(config)
        val route = routeForModel(selectedModel, config)
        connectAgentClient(selectedModel).newSession(model = modelForRoute(selectedModel, route, config))
        lastNotificationText = "Started a new chat"
        isAgentTurnActive = false
        updateNotification()
    }

    private fun handleChatMessage(message: JSONObject) {
        serviceScope.launch {
            if (pendingNewChat && message.optString("type") == "chat.history") {
                val incomingSessionKey = message.optString("sessionKey")
                val activeSessionKey = chatState.sessionKey
                if (activeSessionKey.isNullOrBlank() || incomingSessionKey != activeSessionKey) {
                    return@launch
                }
            }
            val replySessionKey = if (message.optString("type") == "chat.reply_available") {
                message.optString("sessionKey").takeIf { it.isNotBlank() }
            } else {
                null
            }
            chatState = ChatStateReducer.reduce(chatState, message)
            if (replySessionKey != null && overlayController?.isViewingChatSession(replySessionKey) == true) {
                chatState = ChatStateReducer.markSessionRead(chatState, replySessionKey)
            }
            if (
                pendingNewChat &&
                message.optString("type") == "chat.state" &&
                !chatState.sessionKey.isNullOrBlank()
            ) {
                pendingNewChat = false
                chatState = chatState.copy(timeline = emptyList(), usage = ChatUsageSummary())
            }
            overlayController?.setChatState(chatState)
            chatState.status?.takeIf { it.isNotBlank() }?.let { lastNotificationText = it }
            isAgentTurnActive = chatState.isRunning
            syncReplyNotifications()
            updateNotification()
            if (chatClientRoute == ChatClientRoute.Local && isTerminalChatMessage(message)) {
                overlayController?.show()
                overlayController?.openChatPanel(presentation = PanelPresentation.Popup)
            }
        }
    }

    private fun isTerminalChatMessage(message: JSONObject): Boolean {
        return when (message.optString("type")) {
            "chat.final", "chat.error", "chat.reply_available" -> true
            else -> false
        }
    }

    private fun markChatSessionRead(sessionKey: String?) {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return
        if (chatState.unreadCountForSession(key) <= 0) {
            cancelReplyNotification(key)
            return
        }
        chatState = ChatStateReducer.markSessionRead(chatState, key)
        overlayController?.setChatState(chatState)
        syncReplyNotifications()
        updateNotification()
    }

    private fun openChatFromIntent(intent: Intent?) {
        openActiveChatConnection()
        val presentation = panelPresentationFrom(intent)
        if (presentation == PanelPresentation.Popup) {
            overlayController?.show()
        }
        overlayController?.openPanel(presentation)
    }

    private fun openActiveChatConnection() {
        val config = AgentConfigStore.load(this)
        val model = selectedChatModel(config)
        val route = routeForModel(model, config)
        connectAgentClient(model).open(sessionKeyForRoute(route))
    }

    private fun openChatSessionFromNotification(
        sessionKey: String,
        presentation: PanelPresentation
    ) {
        val route = routeForSessionKey(sessionKey)
        connectAgentClient(routeOverride = route).selectSession(sessionKey)
        markChatSessionRead(sessionKey)
        cancelReplyNotification(sessionKey)
        if (Settings.canDrawOverlays(this)) {
            if (presentation == PanelPresentation.Popup) {
                overlayController?.show()
            }
            overlayController?.openChatPanel(
                markCurrentSessionViewed = false,
                presentation = presentation
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun panelPresentationFrom(intent: Intent?): PanelPresentation {
        return when (intent?.getStringExtra(EXTRA_PANEL_PRESENTATION)) {
            PANEL_PRESENTATION_FULLSCREEN -> PanelPresentation.Fullscreen
            PANEL_PRESENTATION_POPUP -> PanelPresentation.Popup
            PANEL_PRESENTATION_AUTO -> notificationPanelPresentation()
            else -> PanelPresentation.Popup
        }
    }

    private fun notificationPanelPresentation(): PanelPresentation {
        return if (overlayController?.isBubbleVisible() == true) {
            PanelPresentation.Popup
        } else {
            PanelPresentation.Fullscreen
        }
    }

    private fun startComposerTranscription() {
        val manager = voiceTranscriptionManager ?: return
        if (!hasMicPermission()) {
            overlayController?.setStatus("Microphone permission is required for transcription.")
            openMicPermissionScreen()
            return
        }

        promoteVoiceForegroundIfAllowed()
        val started = manager.startRecording(this) {
            stopComposerTranscription()
        }
        if (!started) {
            overlayController?.setStatus(manager.currentState().error ?: "Could not start transcription recording.")
            restoreBaseForeground()
        }
    }

    private fun stopComposerTranscription() {
        val manager = voiceTranscriptionManager ?: return
        val state = manager.currentState()
        if (!state.isRecording || state.isTranscribing) {
            return
        }

        serviceScope.launch {
            val transcript = manager.stopAndTranscribe(AgentConfigStore.load(this@AgentForegroundService).openAiApiKey)
            restoreBaseForeground()
            if (transcript != null) {
                overlayController?.insertComposerTranscript(transcript)
            }
        }
    }

    private fun cancelComposerTranscription() {
        voiceTranscriptionManager?.cancelRecording()
        restoreBaseForeground()
        overlayController?.setStatus("Transcription recording canceled.")
    }

    private fun handleTranscriptionState(state: VoiceTranscriptionState) {
        overlayController?.setTranscriptionState(state)
    }

    private fun handleBridgeConnectionState(state: BridgeConnectionState) {
        serviceScope.launch {
            overlayController?.setHostConnectionState(
                HostConnectionState(
                    phase = when (state.phase) {
                        BridgeConnectionPhase.CONNECTING -> HostConnectionPhase.CONNECTING
                        BridgeConnectionPhase.CONNECTED -> HostConnectionPhase.CONNECTED
                        BridgeConnectionPhase.ERROR -> HostConnectionPhase.ERROR
                    },
                    message = state.message
                )
            )
        }
    }

    private fun handleBridgeStatus(text: String, status: String) {
        serviceScope.launch {
            overlayController?.setStatus(text)
            lastNotificationText = text.ifBlank { DEFAULT_NOTIFICATION_TEXT }
            isAgentTurnActive = when (status) {
                "working", "tool" -> true
                "done", "error" -> false
                else -> isAgentTurnActive
            }
            updateNotification()
        }
    }

    private fun requestStopTurn(reason: String) {
        val route = activeChatRoute()
        val client = connectAgentClient(routeOverride = route)
        overlayController?.setStatus("Stop requested")
        lastNotificationText = "Stopping active turn..."
        isAgentTurnActive = true
        updateNotification()
        client.stop(sessionKeyForRoute(route), chatState.activeRunId, reason)
        if (chatClientRoute != ChatClientRoute.Local) {
            webSocketClient?.sendStopRequest(reason)
        }
    }

    private fun foregroundServiceType(includeMicrophone: Boolean): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (includeMicrophone) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                type
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun promoteVoiceForegroundIfAllowed() {
        if (!hasMicPermission()) {
            return
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = true))
        }.onFailure { error ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Voice foreground-service promotion was not allowed; continuing with existing foreground service.", error)
            } else if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Voice foreground-service promotion failed; continuing with existing foreground service.", error)
            } else {
                throw error
            }
        }
    }

    private fun restoreBaseForeground() {
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = false))
        }.onFailure { error ->
            if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Foreground-service restore failed; continuing with existing foreground service.", error)
            } else {
                throw error
            }
        }
    }

    private fun openMicPermissionScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Open Claw Agent", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(REPLY_CHANNEL_ID, "OpenClaw replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Per-session reply notifications from OpenClaw"
            })
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun syncReplyNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        val nextSessions = chatState.unreadReplies.keys
        for (sessionKey in notifiedReplySessions - nextSessions) {
            manager.cancel(replyNotificationId(sessionKey))
        }
        for ((sessionKey, unread) in chatState.unreadReplies) {
            runCatching {
                manager.notify(replyNotificationId(sessionKey), replyNotification(sessionKey, unread))
            }.onFailure { error ->
                Log.w(TAG, "Failed to post reply notification for $sessionKey", error)
            }
        }
        notifiedReplySessions = nextSessions
    }

    private fun cancelReplyNotification(sessionKey: String) {
        getSystemService(NotificationManager::class.java).cancel(replyNotificationId(sessionKey))
        notifiedReplySessions = notifiedReplySessions - sessionKey
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP_TURN)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
        val openPendingIntent = PendingIntent.getService(
            this,
            REQUEST_OPEN_CHAT,
            Intent(this, AgentForegroundService::class.java)
                .setAction(ACTION_OPEN_CHAT)
                .putExtra(EXTRA_PANEL_PRESENTATION, PANEL_PRESENTATION_AUTO),
            flags
        )
        val unreadCount = chatState.totalUnreadReplies
        val notificationText = if (unreadCount > 0) {
            "$unreadCount unread OpenClaw ${if (unreadCount == 1) "reply" else "replies"}"
        } else {
            lastNotificationText
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bubble)
            .setColor(0xFF245BFF.toInt())
            .setContentTitle(when {
                isAgentTurnActive -> "Open Claw Agent working"
                unreadCount > 0 -> "Open Claw Agent replied"
                else -> "Open Claw Agent active"
            })
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(openPendingIntent)
            .setNumber(unreadCount)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "Stop Turn", stopPendingIntent)
            .build()
    }

    private fun replyNotification(sessionKey: String, unread: ChatUnreadReply): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(this, AgentForegroundService::class.java)
            .setAction(ACTION_OPEN_CHAT_SESSION)
            .putExtra(EXTRA_SESSION_KEY, sessionKey)
            .putExtra(EXTRA_PANEL_PRESENTATION, PANEL_PRESENTATION_AUTO)
        val contentIntent = PendingIntent.getService(this, replyNotificationId(sessionKey), openIntent, flags)
        val label = unread.displayNameFor(sessionKey)
        val count = unread.count
        val text = unread.latestPreview
            ?: if (unread.latestStatus == "failed") "OpenClaw failed. Tap to view details." else "Tap to view the reply."
        return NotificationCompat.Builder(this, REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bubble)
            .setColor(0xFF245BFF.toInt())
            .setContentTitle(if (count > 1) "$count unread replies in $label" else "OpenClaw replied in $label")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(count)
            .setOngoing(true)
            .build()
    }

    private fun replyNotificationId(sessionKey: String): Int {
        return REPLY_NOTIFICATION_ID_BASE + (sessionKey.hashCode() and 0x0FFFFFFF)
    }

    private fun broadcastRunningState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_RUNNING, isRunning)
        )
    }

    companion object {
        private const val TAG = "AgentService"
        private const val ACTION_STOP_TURN = "dev.openclawagent.action.STOP_TURN"
        const val ACTION_OPEN_CHAT = "dev.openclawagent.action.OPEN_CHAT"
        private const val ACTION_OPEN_CHAT_SESSION = "dev.openclawagent.action.OPEN_CHAT_SESSION"
        const val ACTION_REFRESH_AVATAR = "dev.openclawagent.action.REFRESH_AVATAR"
        const val ACTION_RESIZE_BUBBLE = "dev.openclawagent.action.RESIZE_BUBBLE"
        const val EXTRA_BUBBLE_SIZE_DP = "dev.openclawagent.extra.BUBBLE_SIZE_DP"
        const val EXTRA_PANEL_PRESENTATION = "panelPresentation"
        const val PANEL_PRESENTATION_POPUP = "popup"
        const val PANEL_PRESENTATION_FULLSCREEN = "fullscreen"
        private const val PANEL_PRESENTATION_AUTO = "auto"
        private const val EXTRA_SESSION_KEY = "sessionKey"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_NOTIFICATION_ID_BASE = 10_000
        private const val REQUEST_OPEN_CHAT = 2
        private const val DEFAULT_NOTIFICATION_TEXT = "Floating bubble and Open Claw bridge are running"
        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val SYSTEM_DIALOG_REASON_HOME_KEY = "homekey"
        const val ACTION_STATE_CHANGED = "dev.openclawagent.action.AGENT_SERVICE_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val CHANNEL_ID = "open-claw-agent"
        private const val REPLY_CHANNEL_ID = "open-claw-agent-replies"
        var isRunning: Boolean = false
            private set
    }
}
