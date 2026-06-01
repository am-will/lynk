package dev.androidagent

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.accessibility.PhoneAccessibilityService
import dev.androidagent.agentchat.AgentChatClient
import dev.androidagent.agentchat.ChatSendDelivery
import dev.androidagent.agentchat.HostAgentChatClient
import dev.androidagent.agentchat.LocalAgentChatClient
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.chat.ChatAttachmentKind
import dev.androidagent.chat.ChatAttachmentPolicy
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatModelCatalog
import dev.androidagent.chat.ChatModelSource
import dev.androidagent.chat.ChatStateReducer
import dev.androidagent.chat.ChatTimelineItem
import dev.androidagent.chat.ChatTimelineKind
import dev.androidagent.chat.ChatUsageSummary
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.net.BridgeConnectionPhase
import dev.androidagent.net.BridgeConnectionState
import dev.androidagent.net.PhoneWebSocketClient
import dev.androidagent.overlay.HostConnectionPhase
import dev.androidagent.overlay.HostConnectionState
import dev.androidagent.overlay.PanelPresentation
import dev.androidagent.overlay.ChatPresentationHelpers
import dev.androidagent.settings.DiagnosticsBackendSnapshot
import dev.androidagent.voice.RealtimeVoiceCoordinator
import dev.androidagent.voice.VoiceRuntimeController
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.transcription.VoiceTranscriptionManager
import dev.androidagent.voice.transcription.VoiceTranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

private const val PHONE_CONTROL_COMPLETION_VISIBLE_MS = 30_000L
private const val CODEX_WORKSPACE_NOT_FOUND_CODE = "codex.workspace_not_found"
private const val OPENCODE_WORKSPACE_NOT_FOUND_CODE = "opencode.workspace_not_found"
private const val CODEX_WORKSPACE_CREATE_MESSAGE = "Folder not found. Would you like to create it?"

class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: OverlayController? = null
    private var webSocketClient: PhoneWebSocketClient? = null
    private var chatClient: AgentChatClient? = null
    private var chatClientRoute: ChatClientRoute? = null
    private var commandExecutor: AccessibilityCommandExecutor? = null
    private var voiceRuntimeController: VoiceRuntimeController? = null
    private var realtimeVoiceCoordinator: RealtimeVoiceCoordinator? = null
    private var voiceTranscriptionManager: VoiceTranscriptionManager? = null
    private var isAgentTurnActive = false
    private var foregroundNotificationActive = false
    private var chatState = ChatState()
    private val chatMessageMutex = Mutex()
    private val newChatCoordinator = NewChatSessionCoordinator()
    private val chatNotifications by lazy { ChatNotificationController(this, ::brandPresentationFor) }
    private var recentsSuppressionStartedAtMs = 0L
    private var recentsRestoreCheck: Runnable? = null
    private var phoneControlAttentionClear: Runnable? = null
    private var realtimeVoiceAttentionClear: Runnable? = null
    private var lastVoiceRuntimeState = VoiceRuntimeState()
    private val phoneControlPetPolicy = PhoneControlAttentionReducer()
    private val realtimeVoicePetPolicy = PhoneControlAttentionReducer()
    private val closeSystemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            when (systemDialogChromeAction(intent.getStringExtra(SYSTEM_DIALOG_REASON))) {
                SystemDialogChromeAction.MinimizePanel -> handleSystemHomePressed()
                SystemDialogChromeAction.SuppressAgentChrome -> handleSystemRecentsOpened()
                SystemDialogChromeAction.None -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        isRunning = true
        bridgeConnectionPhase = BridgeConnectionPhase.CONNECTING
        bridgeConnectionMessage = "Agent service starting"
        broadcastRunningState()
        createChannel()
        AgentConfigStore.load(this).also { config ->
            chatState = chatState.copy(selectedModel = selectedChatModel(config))
            AvatarLibrary.scanOnBoot(applicationContext, config.hostUrl, config.token)
            publishBackendAvailability(config)
        }
        voiceTranscriptionManager = VoiceTranscriptionManager(onStateChanged = ::handleTranscriptionState)
        overlayController = OverlayController(
            context = this,
            onSubmit = { text, attachments -> submitChatText(text, attachments) },
            onStop = { requestStopTurn("Stopped from Android overlay") },
            onDismiss = { stopSelf() },
            onStartVoice = { tryStartVoiceSession() },
            onRevealVoicePet = { activateRealtimeVoicePet() },
            onMinimizeHostApp = { requestAppShellMinimize() },
            onToggleVoiceMute = { voiceRuntimeController?.toggleMute() },
            onStopVoice = { voiceRuntimeController?.stopFromUi() },
            onStartTranscription = { startComposerTranscription() },
            onStopTranscription = { stopComposerTranscription() },
            onCancelTranscription = { cancelComposerTranscription() },
            onSelectChatSession = { sessionKey ->
                maybeUpdateCodexWorkspaceFromSession(sessionKey)
                val route = routeForSessionKey(sessionKey)
                connectAgentClient(routeOverride = route).selectSession(sessionKey)
                markChatSessionRead(sessionKey, force = true)
            },
            onNewChatSession = { startNewChatFromUi() },
            onGetCodexWorkspacePath = { AgentConfigStore.load(this).codexWorkspacePath },
            onSetCodexWorkspacePath = { path -> setCodexWorkspacePath(path) },
            onGetOpenCodeWorkspacePath = { AgentConfigStore.load(this).opencodeWorkspacePath },
            onSetOpenCodeWorkspacePath = { path -> setOpenCodeWorkspacePath(path) },
            onSetChatModel = { model ->
                setChatModelFromUi(model)
            },
            onSetChatHarness = { harnessId ->
                setChatHarnessFromUi(harnessId)
            },
            onSetChatReasoning = { reasoning ->
                val config = AgentConfigStore.load(this)
                val model = selectedChatModel(config)
                if (model.isBlank()) {
                    overlayController?.setStatus("Enable a model harness in Models & Harness first.")
                } else {
                    val route = routeForModel(model, config)
                    connectAgentClient(model).setReasoning(sessionKeyForRoute(route), reasoning)
                }
            },
            onPickChatAttachment = { kind -> requestChatAttachmentPicker(kind) },
            onChatControlCommand = { command, args -> submitChatControlCommand(command, args) },
            onToggleChatTool = { eventId ->
                chatState = ChatStateReducer.toggleTool(chatState, eventId)
                overlayController?.setChatState(chatState)
            },
            onChatSessionViewed = { sessionKey -> markChatSessionRead(sessionKey) },
            onChatSessionOpened = { sessionKey -> markChatSessionRead(sessionKey, force = true) }
        ).also {
            it.setChatState(chatState)
            showPetIfEnabled(it)
        }
        realtimeVoiceCoordinator = RealtimeVoiceCoordinator(
            context = this,
            scope = serviceScope,
            commandExecutor = { commandExecutor() },
            configProvider = { AgentConfigStore.load(this) },
            selectedModel = { config -> selectedChatModel(config) },
            routeForModel = { model, config -> routeForModel(model, config) },
            modelForRoute = { model, route, config -> modelForRoute(model, route, config) },
            selectedReasoningEffort = { chatState.reasoningEffort },
            webSocketClient = { webSocketClient },
            onStatus = ::handleBridgeStatus,
            onChatMessage = { handleChatMessage(it) },
            onRealtimeToolResult = { voiceRuntimeController?.onRealtimeToolResult(it) },
            onRealtimeTaskStatus = { voiceRuntimeController?.onRealtimeTaskStatus(it) }
        )
        voiceRuntimeController = VoiceRuntimeController(
            context = this,
            sendStart = { sdp, config -> realtimeVoiceCoordinator?.sendStart(sdp, config) },
            sendStop = { reason -> realtimeVoiceCoordinator?.sendStop(reason) },
            sendToolCall = { call -> realtimeVoiceCoordinator?.handleToolCall(call) },
            onStateChanged = ::handleVoiceRuntimeStateChanged
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
            ACTION_DISMISS_CHAT_SESSION_NOTIFICATION -> {
                markChatSessionRead(intent.getStringExtra(EXTRA_SESSION_KEY), force = true)
                return START_STICKY
            }
            ACTION_START_VOICE -> {
                if (hasMicPermission()) {
                    promoteVoiceForegroundIfAllowed()
                } else {
                    satisfyForegroundStartWithoutKeepingNotification()
                }
                startVoiceFromShell()
                return START_STICKY
            }
            ACTION_ENSURE_SERVICE -> {
                showPetIfEnabled()
                return START_STICKY
            }
            ACTION_REFRESH_PET_VISIBILITY -> {
                refreshPetVisibility()
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
            ACTION_ATTACH_SHELL_CHAT -> {
                attachShellChatFromIntent()
                return START_STICKY
            }
            ACTION_DETACH_SHELL_CHAT -> {
                val requestedToken = intent.getIntExtra(EXTRA_SHELL_CHAT_TOKEN, 0)
                val requestedActivityId = intent.getIntExtra(EXTRA_SHELL_CHAT_ACTIVITY_ID, 0)
                val ownsActiveShell = requestedToken == activeShellChatContainerToken &&
                    requestedActivityId == activeShellChatActivityId
                if (ownsActiveShell) {
                    overlayController?.detachShellChat()
                    if (requestedToken == shellChatContainerToken && requestedActivityId == shellChatContainerActivityId) {
                        shellChatContainer = null
                        shellChatContainerActivityId = 0
                        shellChatContainerToken = 0
                    }
                    activeShellChatActivityId = 0
                    activeShellChatContainerToken = 0
                    refreshPetVisibility()
                }
                return START_STICKY
            }
            ACTION_ADD_CHAT_ATTACHMENT -> {
                addChatAttachmentFromIntent(intent)
                return START_STICKY
            }
        }
        showPetIfEnabled()
        return START_STICKY
    }

    private fun showPetIfEnabled(controller: OverlayController? = overlayController) {
        if (AgentConfigStore.load(this).petEnabled) {
            controller?.show()
        }
    }

    private fun refreshPetVisibility() {
        if (AgentConfigStore.load(this).petEnabled || phoneControlPetPolicy.overrideVisible || realtimeVoicePetPolicy.overrideVisible) {
            overlayController?.show()
        } else {
            overlayController?.hide()
        }
    }

    private fun applyTransientPetVisibility() {
        if (AgentConfigStore.load(this).petEnabled || phoneControlPetPolicy.overrideVisible || realtimeVoicePetPolicy.overrideVisible) {
            if (Settings.canDrawOverlays(this)) {
                overlayController?.showTransientPet()
            }
        } else {
            overlayController?.hideTransientPet()
        }
    }

    private fun activatePhoneControlPet() {
        val config = AgentConfigStore.load(this)
        phoneControlAttentionClear?.let(mainHandler::removeCallbacks)
        phoneControlAttentionClear = null
        phoneControlPetPolicy.activate(config.petEnabled)
        if (Settings.canDrawOverlays(this)) {
            overlayController?.showTransientPet()
        }
    }

    private fun holdPhoneControlPetAfterCompletion(sessionKey: String?, runId: String?) {
        val config = AgentConfigStore.load(this)
        phoneControlPetPolicy.holdAfterCompletion(config.petEnabled, sessionKey, runId)
        if (!config.petEnabled) {
            if (Settings.canDrawOverlays(this)) {
                overlayController?.showTransientPet()
            }
        }
        schedulePhoneControlPetRestore()
    }

    private fun schedulePhoneControlPetRestore() {
        phoneControlAttentionClear?.let(mainHandler::removeCallbacks)
        val clear = Runnable {
            phoneControlAttentionClear = null
            if (PhoneControlAttentionEffect.HideTransientPet in phoneControlPetPolicy.clearTimedAttention(AgentConfigStore.load(this).petEnabled)) {
                applyTransientPetVisibility()
            }
            clearViewedUnreadReplies()
        }
        phoneControlAttentionClear = clear
        mainHandler.postDelayed(clear, PHONE_CONTROL_COMPLETION_VISIBLE_MS)
    }

    private fun restorePetAfterPhoneControlIfNeeded() {
        if (PhoneControlAttentionEffect.HideTransientPet in phoneControlPetPolicy.restoreOverrideIfNeeded(AgentConfigStore.load(this).petEnabled)) {
            applyTransientPetVisibility()
        }
    }

    private fun rememberPhoneControlRun(sessionKey: String?, runId: String?) {
        phoneControlPetPolicy.rememberRun(sessionKey, runId)
    }

    private fun forgetPhoneControlRun(runId: String?) {
        phoneControlPetPolicy.forgetRun(runId)
    }

    private fun isRememberedPhoneControlRun(runId: String?): Boolean {
        return phoneControlPetPolicy.isRememberedRun(runId)
    }

    private fun shouldPreservePhoneControlUnread(sessionKey: String?): Boolean {
        return phoneControlPetPolicy.shouldPreserveUnread(sessionKey)
    }

    private fun acknowledgePhoneControlReply(sessionKey: String?) {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return
        val wasAttentionSession = phoneControlPetPolicy.attentionSessionKey == key
        val effects = phoneControlPetPolicy.acknowledgeReply(key, AgentConfigStore.load(this).petEnabled)
        if (wasAttentionSession) {
            phoneControlAttentionClear?.let(mainHandler::removeCallbacks)
            phoneControlAttentionClear = null
        }
        if (PhoneControlAttentionEffect.HideTransientPet in effects) {
            applyTransientPetVisibility()
        }
    }

    private fun handlePhoneControlCommandStarted() {
        rememberPhoneControlRun(chatState.sessionKey, chatState.activeRunId)
        activatePhoneControlPet()
    }

    private fun handlePhoneControlCommandFinished() {
        holdPhoneControlPetAfterCompletion(chatState.sessionKey, chatState.activeRunId)
    }

    private fun activateRealtimeVoicePet() {
        val config = AgentConfigStore.load(this)
        realtimeVoiceAttentionClear?.let(mainHandler::removeCallbacks)
        realtimeVoiceAttentionClear = null
        realtimeVoicePetPolicy.activate(config.petEnabled)
        if (Settings.canDrawOverlays(this)) {
            overlayController?.showTransientPet()
        }
    }

    private fun holdRealtimeVoicePetAfterCall() {
        val config = AgentConfigStore.load(this)
        realtimeVoicePetPolicy.holdAfterCompletion(config.petEnabled, sessionKey = null, runId = null)
        if (!config.petEnabled && Settings.canDrawOverlays(this)) {
            overlayController?.showTransientPet()
        }
        scheduleRealtimeVoicePetRestore()
    }

    private fun scheduleRealtimeVoicePetRestore() {
        realtimeVoiceAttentionClear?.let(mainHandler::removeCallbacks)
        val clear = Runnable {
            realtimeVoiceAttentionClear = null
            if (PhoneControlAttentionEffect.HideTransientPet in realtimeVoicePetPolicy.clearTimedAttention(AgentConfigStore.load(this).petEnabled)) {
                applyTransientPetVisibility()
            }
        }
        realtimeVoiceAttentionClear = clear
        mainHandler.postDelayed(clear, REALTIME_VOICE_COMPLETION_VISIBLE_MS)
    }

    private fun handleVoiceRuntimeStateChanged(state: VoiceRuntimeState) {
        val wasActive = lastVoiceRuntimeState.isActive
        lastVoiceRuntimeState = state
        overlayController?.setVoiceState(state)
        if (state.isActive) {
            activateRealtimeVoicePet()
        } else if (wasActive) {
            holdRealtimeVoicePetAfterCall()
        }
    }

    private fun startVoiceFromShell() {
        if (tryStartVoiceSession()) {
            activateRealtimeVoicePet()
            requestAppShellMinimize()
        }
    }

    private fun tryStartVoiceSession(): Boolean {
        val config = AgentConfigStore.load(this)
        val model = selectedChatModel(config)
        if (model.isBlank()) {
            overlayController?.setStatus("Enable a model harness before starting voice.")
            return false
        }
        if (!hasMicPermission()) {
            overlayController?.setStatus("Microphone permission is required for voice mode.")
            openMicPermissionScreen()
            return false
        }
        connectAgentClient(model)
        promoteVoiceForegroundIfAllowed()
        activateRealtimeVoicePet()
        voiceRuntimeController?.start()
        return true
    }

    private fun requestAppShellMinimize() {
        sendBroadcast(Intent(AppShellActivity.ACTION_MINIMIZE_APP).setPackage(packageName))
    }

    override fun onDestroy() {
        voiceRuntimeController?.stopFromUi()
        voiceRuntimeController?.close()
        realtimeVoiceCoordinator?.close()
        realtimeVoiceCoordinator = null
        voiceTranscriptionManager?.close()
        serviceScope.cancel()
        chatClient?.close()
        webSocketClient?.close()
        recentsRestoreCheck?.let(mainHandler::removeCallbacks)
        recentsRestoreCheck = null
        realtimeVoiceAttentionClear?.let(mainHandler::removeCallbacks)
        realtimeVoiceAttentionClear = null
        unregisterCloseSystemDialogsReceiver()
        cancelAllReplyNotifications()
        stopForegroundNotification()
        overlayController?.hide()
        if (activeService === this) {
            activeService = null
        }
        isRunning = false
        bridgeConnectionPhase = BridgeConnectionPhase.ERROR
        bridgeConnectionMessage = "Agent service stopped"
        broadcastRunningState()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
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
                connectWebSocket(config)
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
                } else if (it.optString("type") == "chat.models") {
                    handleHostModelSnapshot(it)
                }
            }
        ).also {
            webSocketClient = it
            it.connect()
        }
    }

    private fun selectedChatModel(config: AgentConfig = AgentConfigStore.load(this)): String {
        val selected = chatState.selectedModel?.takeIf { it.isNotBlank() }
        val options = availableChatModels(config)
        val preferred = listOfNotNull(selected, config.model.takeIf { it.isNotBlank() })
        return preferred.firstOrNull { candidate -> options.any { it.id == candidate } }
            ?: options.firstOrNull { it.available != false }?.id
            ?: options.firstOrNull()?.id
            ?: ""
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

    private fun availableChatModels(config: AgentConfig = AgentConfigStore.load(this)) =
        ChatPresentationHelpers.modelPickerOptions(
            chatState,
            isExperimentalLocalModelAvailable(config),
            config.enabledModelHarnessIds()
        )

    private fun modelForRoute(model: String, route: ChatClientRoute, config: AgentConfig): String {
        return when (route) {
            ChatClientRoute.Local -> AgentModelOptions.LOCAL_LITERT_MODEL_ID
            ChatClientRoute.Host -> model.takeIf { it.isNotBlank() && it != AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?: selectedChatModel(config).takeUnless { it == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?: ""
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

    private fun setCodexWorkspacePath(path: String) {
        val config = AgentConfigStore.load(this)
        AgentConfigStore.save(this, config.copy(codexWorkspacePath = CodexWorkspacePaths.normalizeInput(path)))
    }

    private fun setOpenCodeWorkspacePath(path: String) {
        val config = AgentConfigStore.load(this)
        AgentConfigStore.save(this, config.copy(opencodeWorkspacePath = CodexWorkspacePaths.normalizeInput(path)))
    }

    private fun maybeUpdateCodexWorkspaceFromSession(sessionKey: String) {
        val session = chatState.sessions.firstOrNull { it.key == sessionKey } ?: return
        val harnessId = session.harnessId ?: harnessFromSessionKey(session.key)
        if (harnessId != AgentConfig.HARNESS_CODEX && harnessId != AgentConfig.HARNESS_OPENCODE) return
        val workspacePath = session.workspacePath?.trim()?.takeIf { it.isNotBlank() } ?: return
        when (harnessId) {
            AgentConfig.HARNESS_CODEX -> setCodexWorkspacePath(workspacePath)
            AgentConfig.HARNESS_OPENCODE -> setOpenCodeWorkspacePath(workspacePath)
        }
    }

    private fun isCodexChatSelection(model: String): Boolean {
        return chatState.harnessId == AgentConfig.HARNESS_CODEX ||
            ChatModelCatalog.harnessForModel(model) == AgentConfig.HARNESS_CODEX ||
            chatState.sessionKey?.startsWith("${AgentConfig.HARNESS_CODEX}:") == true
    }

    private fun isOpenCodeChatSelection(model: String): Boolean {
        return chatState.harnessId == AgentConfig.HARNESS_OPENCODE ||
            ChatModelCatalog.harnessForModel(model) == AgentConfig.HARNESS_OPENCODE ||
            chatState.sessionKey?.startsWith("${AgentConfig.HARNESS_OPENCODE}:") == true
    }

    private fun isWorkspaceChatSelection(model: String): Boolean {
        return isCodexChatSelection(model) || isOpenCodeChatSelection(model)
    }

    private fun commandExecutor(): AccessibilityCommandExecutor {
        return commandExecutor ?: AccessibilityCommandExecutor(
            context = this,
            overlayController = overlayController,
            onPhoneControlCommandStarted = { handlePhoneControlCommandStarted() },
            onPhoneControlCommandFinished = { handlePhoneControlCommandFinished() }
        ).also {
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

    private fun handleSystemHomePressed() {
        overlayController?.minimizePanelFromSystemHome()
        restoreAgentChromeAfterRecents()
    }

    private fun handleSystemRecentsOpened() {
        overlayController?.suppressAgentChromeForSystemRecents()
        recentsSuppressionStartedAtMs = SystemClock.uptimeMillis()
        scheduleRecentsRestoreCheck()
    }

    private fun scheduleRecentsRestoreCheck() {
        recentsRestoreCheck?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { maybeRestoreAgentChromeAfterRecents() }
        recentsRestoreCheck = runnable
        mainHandler.postDelayed(runnable, RECENTS_RESTORE_CHECK_MS)
    }

    private fun maybeRestoreAgentChromeAfterRecents() {
        val elapsedMs = SystemClock.uptimeMillis() - recentsSuppressionStartedAtMs
        val accessibility = PhoneAccessibilityService.instance
        val shouldKeepSuppressed = when {
            elapsedMs < RECENTS_MIN_SUPPRESSION_MS -> true
            accessibility == null -> elapsedMs < RECENTS_RESTORE_WITHOUT_ACCESSIBILITY_MS
            accessibility.lastPackageName == null && accessibility.lastActivityClassName == null ->
                elapsedMs < RECENTS_RESTORE_WITHOUT_ACCESSIBILITY_MS
            else -> isSystemRecentsSurface(accessibility.lastPackageName, accessibility.lastActivityClassName)
        }
        if (shouldKeepSuppressed) {
            scheduleRecentsRestoreCheck()
        } else {
            restoreAgentChromeAfterRecents()
        }
    }

    private fun restoreAgentChromeAfterRecents() {
        recentsRestoreCheck?.let(mainHandler::removeCallbacks)
        recentsRestoreCheck = null
        overlayController?.restoreAgentChromeAfterSystemRecents()
    }

    private fun submitChatText(text: String, attachments: List<StoredChatAttachment> = emptyList()): Boolean {
        parseChatDeliveryOverride(text)?.let { override ->
            return submitChatPrompt(override.text, override.delivery, attachments)
        }
        if (attachments.isEmpty() && text.trimStart().startsWith("/")) {
            return submitSlashCommand(text)
        }
        val delivery = activeTurnDelivery(AgentConfigStore.load(this))
        return submitChatPrompt(text, delivery, attachments)
    }

    private fun requestChatAttachmentPicker(kind: ChatAttachmentKind) {
        runCatching {
            startActivity(AppShellActivity.openChatAttachmentPickerIntent(this, kind))
        }.onFailure { error ->
            overlayController?.setStatus(error.message ?: "Could not open file picker")
        }
    }

    private fun addChatAttachmentFromIntent(intent: Intent) {
        val payload = intent.getStringExtra(EXTRA_CHAT_ATTACHMENT_JSON) ?: return
        val attachment = runCatching { StoredChatAttachment.fromStoredJson(JSONObject(payload)) }.getOrNull()
        if (attachment == null) {
            overlayController?.setStatus("Could not read selected attachment")
            return
        }
        overlayController?.addChatAttachment(attachment)
    }

    private fun submitChatPrompt(
        text: String,
        delivery: ChatSendDelivery,
        attachments: List<StoredChatAttachment> = emptyList()
    ): Boolean {
        val requestConfig = AgentConfigStore.load(this)
        val selectedModel = selectedChatModel(requestConfig)
        if (selectedModel.isBlank()) {
            val message = "Enable a model harness in Models & Harness first."
            chatState = chatState.copy(status = message, isRunning = false)
            overlayController?.setChatState(chatState)
            overlayController?.setStatus(message)
            isAgentTurnActive = false
            updateNotification()
            return false
        }
        val route = routeForModel(selectedModel, requestConfig)
        if (route == ChatClientRoute.Host && attachments.isNotEmpty()) {
            val validationError = runCatching { ChatAttachmentPolicy.validateHostSend(attachments) }.exceptionOrNull()
            if (validationError != null) {
                val message = validationError.message ?: "Selected attachment cannot be sent."
                overlayController?.setStatus(message)
                updateNotification()
                return false
            }
        }
        markChatSessionRead(chatState.sessionKey, force = true)
        chatState = ChatStateReducer.localUserMessage(chatState, text, attachments)
        overlayController?.setChatState(chatState)
        val client = connectAgentClient(selectedModel)
        val sent = client.send(
            text = text,
            sessionKey = sessionKeyForRoute(route),
            model = modelForRoute(selectedModel, route, requestConfig),
            reasoningEffort = chatState.reasoningEffort ?: requestConfig.reasoningEffort,
            delivery = delivery,
            attachments = attachments
        )
        if (sent) {
            isAgentTurnActive = true
        } else {
            isAgentTurnActive = false
        }
        updateNotification()
        return sent
    }

    private fun activeTurnDelivery(config: AgentConfig): ChatSendDelivery {
        if (!chatState.isRunning) {
            return ChatSendDelivery.Normal
        }
        return when (config.activeSendMode) {
            ChatActiveSendMode.Queue -> ChatSendDelivery.Queue
            ChatActiveSendMode.Steer -> ChatSendDelivery.Steer
        }
    }

    private fun setChatModelFromUi(model: String) {
        val config = AgentConfigStore.load(this)
        val available = availableChatModels(config)
        if (available.none { it.id == model }) {
            overlayController?.setStatus("Enable this harness in Models & Harness first.")
            return
        }
        if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !isExperimentalLocalModelAvailable(config)) {
            overlayController?.setStatus("Enable Local LiteRT-LLM and import a LiteRT model first.")
            return
        }
        val route = routeForModel(model, config)
        AgentConfigStore.save(this, config.copy(model = model))
        chatState = chatState.copy(
            selectedModel = model,
            status = "Model: ${chatModelDisplayLabel(model, route)}",
            error = null
        )
        publishBackendAvailability(config)
        overlayController?.setChatState(chatState)
        connectAgentClient(model).setModel(sessionKeyForRoute(route), modelForRoute(model, route, config))
        updateNotification()
    }

    private fun setChatHarnessFromUi(harnessId: String) {
        val config = AgentConfigStore.load(this)
        val available = availableChatModels(config)
        val model = ChatPresentationHelpers.defaultModelForHarness(
            harnessId = harnessId,
            configuredDefaultModel = config.defaultModelForHarness(harnessId),
            models = available,
            enabledHarnessIds = config.enabledModelHarnessIds()
        )
        if (model.isNullOrBlank()) {
            overlayController?.setStatus("No models available for ${ChatPresentationHelpers.harnessLabel(harnessId)}.")
            return
        }
        setChatModelFromUi(model)
    }

    private fun chatModelDisplayLabel(model: String, route: ChatClientRoute): String {
        if (route == ChatClientRoute.Local) return "Local LiteRT-LM"
        val option = availableChatModels().firstOrNull { it.id == model }
        val harness = option?.let { ChatPresentationHelpers.modelHarnessLabel(it) }
        val label = option?.label ?: AgentModelOptions.modelLabel(model)
        return harness?.let { "$it / $label" } ?: label
    }

    private fun brandPresentationFor(state: ChatState, sessionKey: String? = null): dev.androidagent.overlay.ClientBrandPresentation {
        val config = AgentConfigStore.load(this)
        val models = availableChatModels(config)
        val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
        val session = state.sessions.firstOrNull { it.key == sessionKey }
        val unread = sessionKey?.let { state.unreadReplies[it] }
        return ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = ChatPresentationHelpers.selectedModelId(
                session?.model
                ?: unread?.source?.model
                ?: state.selectedModel
                ?: selectedChatModel(),
                localLiteRtAvailable,
                models
            ),
            models = models,
            harnessId = (
                session?.harnessId
                    ?: unread?.source?.harnessId
                    ?: harnessFromSessionKey(sessionKey)
                    ?: state.harnessId
                )?.takeIf { config.isModelHarnessEnabled(it) },
            localLiteRtAvailable = localLiteRtAvailable
        )
    }

    private fun harnessFromSessionKey(sessionKey: String?): String? {
        val prefix = sessionKey?.substringBefore(":", missingDelimiterValue = "")?.lowercase()
        return when (prefix) {
            "hermes", "codex", "opencode", "local" -> prefix
            else -> null
        }
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
        isAgentTurnActive = command != "status"
        updateNotification()
        return true
    }

    private fun submitChatControlCommand(command: String, args: JSONObject) {
        val notice = ChatStateReducer.localControlNotice(command, args)
        if (!notice.isNullOrBlank()) {
            chatState = ChatStateReducer.localControlCommand(chatState, command, args)
            overlayController?.setChatState(chatState)
            updateNotification()
        }
        connectAgentClient(routeOverride = activeChatRoute()).controlCommand(command, args)
    }

    private fun startNewChatFromUi() {
        val config = AgentConfigStore.load(this)
        val selectedModel = selectedModelForNewChat(config)
        if (selectedModel.isBlank()) {
            val message = "Enable a model harness in Models & Harness first."
            chatState = chatState.copy(status = message, isRunning = false)
            overlayController?.setChatState(chatState)
            overlayController?.setStatus(message)
            isAgentTurnActive = false
            updateNotification()
            return
        }
        val route = routeForModel(selectedModel, config)
        val candidateWorkspacePath = defaultWorkspacePathForModel(selectedModel, config)
            ?: currentHostWorkspacePathForModel(selectedModel)
        val workspacePath = candidateWorkspacePath
            .takeIf { route == ChatClientRoute.Host && isWorkspaceChatSelection(selectedModel) && CodexWorkspacePaths.hasDefault(it) }
        beginNewChatAttempt(
            request = PendingNewChatRequest(
                selectedModel = selectedModel,
                route = route,
                model = modelForRoute(selectedModel, route, config),
                workspacePath = workspacePath,
                previousSessionKey = chatState.sessionKey
            ),
            createWorkspaceIfMissing = false
        )
    }

    private fun selectedModelForNewChat(config: AgentConfig): String {
        val activeHarness = chatState.harnessId
            ?: ChatModelCatalog.harnessFromSessionKey(chatState.sessionKey)
        val models = availableChatModels(config)
        val activeHarnessModel = ChatModelCatalog.defaultModelForHarness(
            harnessId = activeHarness,
            configuredDefaultModel = chatState.selectedModel ?: config.model,
            models = models,
            enabledHarnessIds = config.enabledModelHarnessIds()
        )
        return activeHarnessModel ?: selectedChatModel(config)
    }

    private fun currentHostWorkspacePathForModel(model: String): String? {
        val activeSessionKey = chatState.sessionKey?.takeIf { it.isNotBlank() } ?: return null
        val session = chatState.sessions.firstOrNull { it.key == activeSessionKey } ?: return null
        val harnessId = session.harnessId ?: harnessFromSessionKey(session.key)
        if (
            harnessId != AgentConfig.HARNESS_CODEX &&
            harnessId != AgentConfig.HARNESS_OPENCODE
        ) {
            return null
        }
        if (harnessId != ChatModelCatalog.harnessForModel(model)) return null
        return session.workspacePath?.trim()?.takeIf { CodexWorkspacePaths.hasDefault(it) }
    }

    private fun defaultWorkspacePathForModel(model: String, config: AgentConfig): String? {
        return when (ChatModelCatalog.harnessForModel(model)) {
            AgentConfig.HARNESS_CODEX -> config.codexWorkspacePath
            AgentConfig.HARNESS_OPENCODE -> config.opencodeWorkspacePath
            else -> null
        }
    }

    private fun beginNewChatAttempt(request: PendingNewChatRequest, createWorkspaceIfMissing: Boolean) {
        markChatSessionRead(chatState.sessionKey, force = true)
        newChatCoordinator.begin(request)
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
        connectAgentClient(request.selectedModel).newSession(
            model = request.model,
            workspacePath = request.workspacePath,
            createWorkspaceIfMissing = createWorkspaceIfMissing && request.route == ChatClientRoute.Host && isWorkspaceChatSelection(request.selectedModel)
        )
        isAgentTurnActive = false
        updateNotification()
    }

    private fun handleChatMessage(message: JSONObject) {
        serviceScope.launch {
            chatMessageMutex.withLock {
                if (newChatCoordinator.pending && isCodexWorkspaceNotFoundError(message)) {
                    val retryRequest = newChatCoordinator.consumeWorkspaceNotFoundRetry()
                    chatState = chatState.copy(
                        isRunning = false,
                        status = "Folder not found",
                        error = null
                    )
                    overlayController?.setChatState(chatState)
                    isAgentTurnActive = false
                    updateNotification()
                    promptCreateCodexWorkspace(retryRequest)
                    return@withLock
                }
                if (newChatCoordinator.pending && message.optString("type") == "chat.history") {
                    val incomingSessionKey = message.optString("sessionKey").takeIf { it.isNotBlank() }
                    val activeSessionKey = chatState.sessionKey
                    if (newChatCoordinator.shouldIgnoreHistory(incomingSessionKey, activeSessionKey)) {
                        return@withLock
                    }
                }
                val replySessionKey = if (message.optString("type") == "chat.reply_available") {
                    message.optString("sessionKey").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val messageRunId = message.optString("runId").takeIf { it.isNotBlank() }
                val messageSessionKey = message.optString("sessionKey").takeIf { it.isNotBlank() }
                val phoneControlStarted = isPhoneControlStartMessage(message)
                val phoneControlCompleted = isTerminalChatMessage(message) && isRememberedPhoneControlRun(messageRunId)
                chatState = ChatStateReducer.reduce(chatState, message)
                if (newChatCoordinator.pending && message.optString("type") == "chat.history") {
                    newChatCoordinator.markHistoryLoaded(chatState.sessionKey)
                }
                publishBackendAvailability()
                if (phoneControlStarted) {
                    rememberPhoneControlRun(
                        sessionKey = messageSessionKey ?: chatState.sessionKey,
                        runId = messageRunId ?: chatState.activeRunId
                    )
                    activatePhoneControlPet()
                }
                if (phoneControlCompleted) {
                    holdPhoneControlPetAfterCompletion(
                        sessionKey = messageSessionKey ?: chatState.sessionKey,
                        runId = messageRunId
                    )
                    forgetPhoneControlRun(messageRunId)
                }
                if (replySessionKey != null && overlayController?.isViewingChatSession(replySessionKey) == true) {
                    if (!shouldPreservePhoneControlUnread(replySessionKey)) {
                        chatState = ChatStateReducer.markSessionRead(chatState, replySessionKey)
                    }
                }
                if (
                    newChatCoordinator.pending &&
                    message.optString("type") == "chat.state" &&
                    !chatState.sessionKey.isNullOrBlank()
                ) {
                    val hasLoadedNewHistory = newChatCoordinator.completeIfStateLoaded(chatState.sessionKey) == true
                    if (!hasLoadedNewHistory) {
                        chatState = chatState.copy(timeline = emptyList(), usage = ChatUsageSummary())
                    }
                }
                if (newChatCoordinator.pending && message.optString("type") == "chat.error") {
                    newChatCoordinator.clear()
                }
                overlayController?.setChatState(chatState)
                isAgentTurnActive = chatState.isRunning
                syncReplyNotifications()
                updateNotification()
                if (shouldAutoOpenLocalTerminalMessage(message)) {
                    overlayController?.show()
                    overlayController?.openChatPanel(presentation = PanelPresentation.Popup)
                }
            }
        }
    }

    private fun isCodexWorkspaceNotFoundError(message: JSONObject): Boolean {
        if (message.optString("type") != "chat.error") return false
        val code = message.optString("code")
        return code == CODEX_WORKSPACE_NOT_FOUND_CODE || code == OPENCODE_WORKSPACE_NOT_FOUND_CODE
    }

    private fun promptCreateCodexWorkspace(request: PendingNewChatRequest?) {
        val retryRequest = request ?: return
        if (retryRequest.route != ChatClientRoute.Host || retryRequest.workspacePath.isNullOrBlank()) return
        if (!isWorkspaceChatSelection(retryRequest.selectedModel)) return
        if (!newChatCoordinator.startWorkspacePrompt()) return
        serviceScope.launch {
            val allow = overlayController
                ?.askConfirmation(CODEX_WORKSPACE_CREATE_MESSAGE, retryRequest.workspacePath)
                ?.await() == true
            newChatCoordinator.finishWorkspacePrompt()
            if (allow) {
                beginNewChatAttempt(retryRequest, createWorkspaceIfMissing = true)
            } else {
                chatState = chatState.copy(status = "Folder not found")
                overlayController?.setChatState(chatState)
                isAgentTurnActive = false
                updateNotification()
            }
        }
    }

    private fun isPhoneControlStartMessage(message: JSONObject): Boolean {
        if (message.optString("type") != "chat.state") return false
        if (message.optString("taskKind") == "phone") return true
        val status = message.optString("status").lowercase()
        return status.contains("phone task") || status.contains("android phone tools")
    }

    private fun handleHostModelSnapshot(message: JSONObject) {
        serviceScope.launch {
            chatState = ChatStateReducer.reduce(chatState, message)
            publishBackendAvailability()
            overlayController?.setChatState(chatState)
        }
    }

    private fun publishBackendAvailability(config: AgentConfig = AgentConfigStore.load(this)) {
        val models = availableChatModels(config).filter { it.available != false }
        val modelCounts = models
            .groupingBy { ChatPresentationHelpers.modelHarnessId(it) }
            .eachCount()
        val liveHostModels = chatState.hostModels.ifEmpty {
            if (chatState.modelSource == ChatModelSource.HOST) chatState.models else emptyList()
        }
            .filter { it.available != false }
            .filter { ChatPresentationHelpers.modelHarnessId(it) != AgentConfig.HARNESS_LOCAL }
            .filter { ChatPresentationHelpers.modelHarnessId(it) in config.enabledModelHarnessIds() }
        val liveHostModelsByHarness = liveHostModels.groupBy { ChatPresentationHelpers.modelHarnessId(it) }
        val activeHarnessIds = buildSet {
            chatState.harnessId?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            chatState.selectedModel?.takeIf { it.isNotBlank() }?.let { add(ChatModelCatalog.harnessForModel(it)) }
            chatState.sessions.forEach { session ->
                session.harnessId?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                session.model?.takeIf { it.isNotBlank() }?.let { add(ChatModelCatalog.harnessForModel(it)) }
            }
        }
        DiagnosticsBackendSnapshot.update(modelCounts, activeHarnessIds, liveHostModelsByHarness)
    }

    private fun isTerminalChatMessage(message: JSONObject): Boolean {
        return when (message.optString("type")) {
            "chat.final", "chat.error", "chat.reply_available" -> true
            else -> false
        }
    }

    private fun shouldAutoOpenLocalTerminalMessage(message: JSONObject): Boolean {
        if (chatClientRoute != ChatClientRoute.Local) return false
        if (!isTerminalChatMessage(message)) return false
        val runId = message.optString("runId")
        return runId.startsWith(LOCAL_REALTIME_RUN_PREFIX)
    }

    private fun markChatSessionRead(sessionKey: String?, force: Boolean = false) {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return
        if (!force && shouldPreservePhoneControlUnread(key)) {
            return
        }
        acknowledgePhoneControlReply(key)
        if (chatState.unreadCountForSession(key) <= 0) {
            cancelReplyNotification(key)
            return
        }
        chatState = ChatStateReducer.markSessionRead(chatState, key)
        overlayController?.setChatState(chatState)
        syncReplyNotifications()
        updateNotification()
    }

    private fun clearViewedUnreadReplies() {
        chatState.unreadReplies.keys
            .filter { overlayController?.isViewingChatSession(it) == true }
            .toList()
            .forEach { markChatSessionRead(it) }
    }

    private fun attachShellChatFromIntent() {
        val container = shellChatContainer ?: return
        val requestedToken = shellChatContainerToken
        val requestedActivityId = shellChatContainerActivityId
        activeShellChatContainerToken = requestedToken
        activeShellChatActivityId = requestedActivityId
        openActiveChatConnection()
        overlayController?.attachShellChat(container)
    }

    private fun openChatFromIntent(intent: Intent?) {
        restoreAgentChromeAfterRecents()
        openActiveChatConnection()
        markChatSessionRead(chatState.sessionKey, force = true)
        val presentation = panelPresentationFrom(intent)
        if (presentation == PanelPresentation.Popup) {
            overlayController?.show()
        }
        overlayController?.openPanel(presentation)
    }

    private fun openActiveChatConnection() {
        val config = AgentConfigStore.load(this)
        val model = selectedChatModel(config)
        if (model.isBlank()) {
            overlayController?.setStatus("Enable a model harness in Models & Harness first.")
            return
        }
        val route = routeForModel(model, config)
        connectAgentClient(model).open(sessionKeyForRoute(route))
    }

    private fun openChatSessionFromNotification(
        sessionKey: String,
        presentation: PanelPresentation
    ) {
        restoreAgentChromeAfterRecents()
        maybeUpdateCodexWorkspaceFromSession(sessionKey)
        val route = routeForSessionKey(sessionKey)
        connectAgentClient(routeOverride = route).selectSession(sessionKey)
        markChatSessionRead(sessionKey, force = true)
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
                Intent(this, AppShellActivity::class.java)
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
        bridgeConnectionPhase = state.phase
        bridgeConnectionMessage = state.message
        broadcastRunningState()
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
            startForegroundNotification(includeMicrophone = true)
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
            stopForegroundNotification()
        }.onFailure { error ->
            if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Foreground-service restore failed; continuing with existing foreground service.", error)
            } else {
                throw error
            }
        }
    }

    private fun satisfyForegroundStartWithoutKeepingNotification() {
        runCatching {
            startForegroundNotification(includeMicrophone = false)
            stopForegroundNotification()
        }.onFailure { error ->
            if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Foreground-service bootstrap failed; continuing as a started service.", error)
            } else {
                throw error
            }
        }
    }

    private fun startForegroundNotification(includeMicrophone: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            chatNotifications.foregroundNotification(chatState),
            foregroundServiceType(includeMicrophone = includeMicrophone)
        )
        foregroundNotificationActive = true
    }

    private fun stopForegroundNotification() {
        if (!foregroundNotificationActive) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundNotificationActive = false
    }

    private fun openMicPermissionScreen() {
        startActivity(
            Intent(this, AppShellActivity::class.java)
                .putExtra(AppShellActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun createChannel() {
        chatNotifications.createChannels()
    }

    private fun updateNotification() {
        if (foregroundNotificationActive) {
            chatNotifications.updateForeground(chatState)
        }
    }

    private fun syncReplyNotifications() {
        chatNotifications.syncReplies(chatState)
    }

    private fun cancelReplyNotification(sessionKey: String) {
        chatNotifications.cancelReply(sessionKey)
    }

    private fun cancelAllReplyNotifications() {
        chatNotifications.cancelAllReplies(chatState)
    }

    private fun broadcastRunningState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_RUNNING, isRunning)
                .putExtra(EXTRA_BRIDGE_CONNECTED, isBridgeConnected())
                .putExtra(EXTRA_BRIDGE_CONNECTION_MESSAGE, bridgeConnectionMessage)
        )
    }

    companion object {
        private const val TAG = "AgentService"
        private const val ACTION_STOP_TURN = ChatNotificationController.ACTION_STOP_TURN
        const val ACTION_OPEN_CHAT = ChatNotificationController.ACTION_OPEN_CHAT
        const val ACTION_ENSURE_SERVICE = "app.lynk.action.ENSURE_SERVICE"
        const val ACTION_START_VOICE = "app.lynk.action.START_VOICE"
        private const val ACTION_OPEN_CHAT_SESSION = ChatNotificationController.ACTION_OPEN_CHAT_SESSION
        private const val ACTION_DISMISS_CHAT_SESSION_NOTIFICATION = ChatNotificationController.ACTION_DISMISS_CHAT_SESSION_NOTIFICATION
        const val ACTION_REFRESH_AVATAR = "app.lynk.action.REFRESH_AVATAR"
        const val ACTION_RESIZE_BUBBLE = "app.lynk.action.RESIZE_BUBBLE"
        const val ACTION_REFRESH_PET_VISIBILITY = "app.lynk.action.REFRESH_PET_VISIBILITY"
        const val ACTION_ATTACH_SHELL_CHAT = "app.lynk.action.ATTACH_SHELL_CHAT"
        const val ACTION_DETACH_SHELL_CHAT = "app.lynk.action.DETACH_SHELL_CHAT"
        const val ACTION_ADD_CHAT_ATTACHMENT = "app.lynk.action.ADD_CHAT_ATTACHMENT"
        const val EXTRA_SHELL_CHAT_ACTIVITY_ID = "shellChatActivityId"
        const val EXTRA_SHELL_CHAT_TOKEN = "shellChatToken"
        const val EXTRA_CHAT_ATTACHMENT_JSON = "chatAttachmentJson"
        const val EXTRA_BUBBLE_SIZE_DP = "app.lynk.extra.BUBBLE_SIZE_DP"
        const val EXTRA_PANEL_PRESENTATION = ChatNotificationController.EXTRA_PANEL_PRESENTATION
        const val PANEL_PRESENTATION_POPUP = "popup"
        const val PANEL_PRESENTATION_FULLSCREEN = "fullscreen"
        private const val PANEL_PRESENTATION_AUTO = ChatNotificationController.PANEL_PRESENTATION_AUTO
        private const val EXTRA_SESSION_KEY = ChatNotificationController.EXTRA_SESSION_KEY
        private const val NOTIFICATION_ID = ChatNotificationController.NOTIFICATION_ID
        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val RECENTS_RESTORE_CHECK_MS = 350L
        private const val RECENTS_MIN_SUPPRESSION_MS = 700L
        private const val RECENTS_RESTORE_WITHOUT_ACCESSIBILITY_MS = 2_500L
        private const val REALTIME_VOICE_COMPLETION_VISIBLE_MS = 10_000L
        private const val LOCAL_REALTIME_RUN_PREFIX = "local_realtime_"
        const val ACTION_STATE_CHANGED = "app.lynk.action.AGENT_SERVICE_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_BRIDGE_CONNECTED = "bridgeConnected"
        const val EXTRA_BRIDGE_CONNECTION_MESSAGE = "bridgeConnectionMessage"
        const val CHANNEL_ID = ChatNotificationController.CHANNEL_ID
        var isRunning: Boolean = false
            private set
        @Volatile
        private var bridgeConnectionPhase: BridgeConnectionPhase = BridgeConnectionPhase.ERROR
        @Volatile
        private var bridgeConnectionMessage: String = "Agent service stopped"
        @Volatile
        var shellChatContainer: FrameLayout? = null
        var shellChatContainerActivityId: Int = 0
        var shellChatContainerToken: Int = 0
        private var activeShellChatActivityId: Int = 0
        private var activeShellChatContainerToken: Int = 0
        @Volatile
        private var activeService: AgentForegroundService? = null

        fun consumeShellChatBackPress(): Boolean {
            return activeService?.overlayController?.consumeShellBackPress() == true
        }

        fun isBridgeConnected(): Boolean {
            return isRunning && bridgeConnectionPhase == BridgeConnectionPhase.CONNECTED
        }
    }
}
