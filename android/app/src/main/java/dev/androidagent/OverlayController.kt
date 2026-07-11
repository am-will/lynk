package dev.androidagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.FrameLayout
import dev.androidagent.chat.ChatAttachmentKind
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.chat.ChatCommandOption
import dev.androidagent.chat.ChatModelCatalog
import dev.androidagent.chat.ChatModelSource
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.overlay.BubbleOverlay
import dev.androidagent.overlay.ChatPickerRows
import dev.androidagent.overlay.ChatPresentationHelpers
import dev.androidagent.overlay.ChatTimelineBinder
import dev.androidagent.overlay.ComposerAttachmentTray
import dev.androidagent.overlay.ConfirmationOverlay
import dev.androidagent.overlay.WorkspaceSessionPickerSections
import dev.androidagent.overlay.HostConnectionCopy
import dev.androidagent.overlay.HostConnectionIndicatorButton
import dev.androidagent.overlay.HostConnectionPhase
import dev.androidagent.overlay.HostConnectionState
import dev.androidagent.overlay.PanelBounds
import dev.androidagent.overlay.PanelChrome
import dev.androidagent.overlay.PanelKeyboardLayout
import dev.androidagent.overlay.PanelPresentation
import dev.androidagent.overlay.SlashCommandAutocomplete
import dev.androidagent.overlay.SlashToken
import dev.androidagent.overlay.VoicePanel
import dev.androidagent.overlay.detachOverlayView
import dev.androidagent.overlay.hostConnectionColor
import dev.androidagent.overlay.isOverlayAttached
import dev.androidagent.ui.AnchoredPicker
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.StatusUpdateView
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.hideFromAccessibility
import dev.androidagent.ui.updateAccessibilityState
import org.json.JSONObject
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.transcription.VoiceTranscriptionState
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private class SkillTokenSpan(color: Int) : ForegroundColorSpan(color)

private const val SESSION_TOGGLE_REVEAL_BIAS = 0.35f
private const val COMMAND_GROUP_TOGGLE_REVEAL_BIAS = 0.5f

internal fun shouldMinimizeHostAppAfterVoiceStart(presentation: PanelPresentation): Boolean {
    return when (presentation) {
        PanelPresentation.Popup -> false
        PanelPresentation.Fullscreen,
        PanelPresentation.Shell -> true
    }
}

class OverlayController(
    private val context: Context,
    private val onSubmit: (String, List<StoredChatAttachment>) -> Boolean,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onStartVoice: () -> Boolean,
    private val onRevealVoicePet: () -> Unit,
    private val onMinimizeHostApp: () -> Unit,
    private val onToggleVoiceMute: () -> Unit,
    private val onStopVoice: () -> Unit,
    private val onStartTranscription: () -> Unit,
    private val onStopTranscription: () -> Unit,
    private val onCancelTranscription: () -> Unit,
    private val onSelectChatSession: (String) -> Unit = {},
    private val onNewChatSession: () -> Unit = {},
    private val onGetWorkspacePath: (String) -> String = { "" },
    private val onSetWorkspacePath: (String, String) -> Unit = { _, _ -> },
    private val onSetChatModel: (String) -> Unit = {},
    private val onSetChatHarness: (String) -> Unit = {},
    private val onSetChatReasoning: (String) -> Unit = {},
    private val onPickChatAttachment: (ChatAttachmentKind) -> Unit = {},
    private val onChatControlCommand: (String, JSONObject) -> Unit = { _, _ -> },
    private val onToggleChatTool: (String) -> Unit = {},
    private val onChatSessionViewed: (String) -> Unit = {},
    private val onChatSessionOpened: (String) -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bubbleOverlay = BubbleOverlay(
        context = context,
        windowManager = windowManager,
        onTogglePanel = { togglePanel(PanelPresentation.Popup) },
        onDismissPanelBeforeBubbleDismiss = { dismissPanel(cancelTranscription = false) },
        onDismiss = onDismiss
    )
    private val chatTimelineBinder = ChatTimelineBinder(
        context = context,
        onToggleChatTool = onToggleChatTool,
        onChatToolAction = { action ->
            onChatControlCommand(action.command, action.args)
        }
    )
    private val panelChrome = PanelChrome(
        context = context,
        windowManager = windowManager,
        callbacks = object : PanelChrome.Callbacks {
            override fun onBackPressed() {
                handlePanelBackPressed()
            }

            override fun onScrimClicked() {
                dismissPanel()
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                handlePanelWindowFocusChanged(hasWindowFocus)
            }

            override fun isPickerShowing(): Boolean {
                return isAnchoredPickerShowing()
            }
        }
    )
    private val confirmationOverlay = ConfirmationOverlay(context, windowManager)
    private val voicePanel = VoicePanel(
        context = context,
        onToggleVoiceMute = onToggleVoiceMute,
        onStopVoice = onStopVoice
    )
    private val attachmentTray = ComposerAttachmentTray(context) {
        renderComposerActionButtons(tokens(), lastChatState, lastTranscriptionState)
    }
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelScrimView: View? = null
    private var panelScrimParams: WindowManager.LayoutParams? = null
    private var statusText: StatusUpdateView? = null
    private var lastVoiceState = VoiceRuntimeState()
    private var panelDismissAnimating = false
    private var panelOpenAnimator: Animator? = null
    private var panelScrimOpenAnimator: Animator? = null
    private var panelCloseAnimator: Animator? = null
    private var panelScrimCloseAnimator: Animator? = null
    private var suppressNextPanelViewedCallback = false
    // Tracks whether the chat panel currently owns Android window focus.
    // The panel can stay attached (TYPE_APPLICATION_OVERLAY) while the user has
    // navigated away (home, app switcher, another app on top, etc.). Treat
    // "actively viewing the chat" as panel attached AND has window focus, so we
    // don't silently mark replies as read while the user is on the home screen.
    private var panelHasWindowFocus = false
    private var lastChatState = ChatState()
    private var chatRenderSequence = 0L
    private var showToolCalls = false
    private var suppressComposerAutocomplete = false
    private var composerContainer: LinearLayout? = null
    private var keyboardSpacerView: View? = null
    private var sendStopButton: ImageButton? = null
    private var modelButton: TextView? = null
    private var reasoningButton: TextView? = null
    private var contextUsageView: ContextUsageView? = null
    private var composerInput: EditText? = null
    private var composerDraftText = ""
    private var transcriptionMicButton: ImageButton? = null
    private var lastTranscriptionState = VoiceTranscriptionState()
    private var automationSuppressionDepth = 0
    private var restoreBubbleAfterAutomation = false
    private var restoreBubbleAfterFullscreen = false
    private var restoreBubbleAfterShellChat = false
    private var restorePanelAfterAutomation = false
    private var restorePanelScrimAfterAutomation = false
    private var restorePanelFocusAfterAutomation = false
    private var restoreComposerFocusAfterAutomation = false
    private var recentsSuppressionActive = false
    private var restoreBubbleAfterRecents = false
    private var keyboardFallbackSuppressed = false
    private var stableKeyboardFrameObserved = false
    private var lastUsableKeyboardTop: Int? = null
    private var activePanelPresentation = PanelPresentation.Popup
    private var pendingPickerShowRunnable: Runnable? = null

    private var panelHost: FrameLayout? = null
    private var panelContent: LinearLayout? = null
    private var anchoredPicker: AnchoredPicker? = null
    private val expandedModelHarnesses = mutableSetOf<String>()
    private val expandedSessionWorkspaces = mutableSetOf<String>()
    private var expandedSessionQuickChats = false
    private val expandedCommandPickerGroups = mutableSetOf<String>()
    private var modelPickerActiveHarnessId: String? = null
    private var sessionPickerActiveGroupKey: String? = null
    private var activeSessionsMenuAnchor: View? = null
    private var headerSessionAnchor: View? = null
    private var headerSessionChevron: ImageView? = null
    private var headerBrandLogo: ImageView? = null
    private var headerBrandTitle: TextView? = null
    private var connectionIndicatorButton: HostConnectionIndicatorButton? = null
    private var connectionPopupView: View? = null
    private var connectionPopupScrimView: View? = null
    private var lastHostConnectionState = HostConnectionState(
        phase = HostConnectionPhase.CONNECTING,
        message = "Checking host connection..."
    )

    fun show() {
        showInternal(allowDuringFullscreenPanel = false)
    }

    fun showForPhoneControl() {
        showTransientPet()
    }

    fun hidePhoneControlPet() {
        hideTransientPet()
    }

    fun showTransientPet() {
        showInternal(allowDuringFullscreenPanel = false)
    }

    fun hideTransientPet() {
        restoreBubbleAfterAutomation = false
        restoreBubbleAfterFullscreen = false
        restoreBubbleAfterRecents = false
        bubbleOverlay.hide()
    }

    private fun showInternal(allowDuringFullscreenPanel: Boolean) {
        if (
            !Settings.canDrawOverlays(context) ||
            bubbleOverlay.isVisible ||
            automationSuppressionDepth > 0 ||
            isShellChatActivelyViewed() ||
            (!allowDuringFullscreenPanel && isFullscreenPanelAttached())
        ) {
            return
        }
        bubbleOverlay.show(lastVoiceState, lastChatState)
    }

    fun hide() {
        automationSuppressionDepth = 0
        restoreBubbleAfterAutomation = false
        restoreBubbleAfterFullscreen = false
        restoreBubbleAfterShellChat = false
        recentsSuppressionActive = false
        restoreBubbleAfterRecents = false
        bubbleOverlay.hide()
        dismissPanel()
        confirmationOverlay.dismiss()
    }

    fun suppressAgentChromeForAutomation() {
        automationSuppressionDepth += 1
        if (automationSuppressionDepth > 1) {
            return
        }

        restoreBubbleAfterAutomation = bubbleOverlay.isVisible
        restorePanelAfterAutomation = isOverlayAttached(panelView)
        restorePanelScrimAfterAutomation = isOverlayAttached(panelScrimView)
        restorePanelFocusAfterAutomation = panelView?.hasFocus() == true
        restoreComposerFocusAfterAutomation = composerInput?.hasFocus() == true
        // Automation suppression only clears our chrome; it must not stop turns,
        // hang up voice, clear the chat modal's state, or dismiss the foreground service.
        detachOverlayView(windowManager, panelView)
        detachOverlayView(windowManager, panelScrimView)
        bubbleOverlay.detachForAutomation()
    }

    fun restoreAgentChromeAfterAutomation() {
        if (automationSuppressionDepth == 0) {
            return
        }
        automationSuppressionDepth -= 1
        if (automationSuppressionDepth > 0) {
            return
        }

        val shouldRestoreBubble = restoreBubbleAfterAutomation
        val shouldRestorePanel = restorePanelAfterAutomation
        val shouldRestorePanelScrim = restorePanelScrimAfterAutomation
        val shouldRestorePanelFocus = restorePanelFocusAfterAutomation
        val shouldRestoreComposerFocus = restoreComposerFocusAfterAutomation
        restoreBubbleAfterAutomation = false
        restorePanelAfterAutomation = false
        restorePanelScrimAfterAutomation = false
        restorePanelFocusAfterAutomation = false
        restoreComposerFocusAfterAutomation = false
        if (Settings.canDrawOverlays(context)) {
            restoreSuppressedPanel(
                restoreScrim = shouldRestorePanelScrim,
                restorePanel = shouldRestorePanel,
                restorePanelFocus = shouldRestorePanelFocus,
                restoreComposerFocus = shouldRestoreComposerFocus
            )
        }
        if (shouldRestoreBubble) {
            show()
        }
    }

    fun setStatus(text: String) {
        statusText?.setText(chatStatusText(text, lastChatState))
    }

    fun minimizePanelFromSystemHome() {
        mainHandler.post {
            if (panelView != null) {
                dismissPanel()
            }
        }
    }

    fun suppressAgentChromeForSystemRecents() {
        mainHandler.post {
            if (recentsSuppressionActive) {
                return@post
            }
            recentsSuppressionActive = true
            restoreBubbleAfterRecents = bubbleOverlay.isVisible || restoreBubbleAfterFullscreen
            restoreBubbleAfterFullscreen = false
            dismissPanel(cancelTranscription = false, force = true)
            bubbleOverlay.detachForAutomation()
            confirmationOverlay.dismiss()
        }
    }

    fun restoreAgentChromeAfterSystemRecents() {
        mainHandler.post {
            if (!recentsSuppressionActive) {
                return@post
            }
            val shouldRestoreBubble = restoreBubbleAfterRecents
            recentsSuppressionActive = false
            restoreBubbleAfterRecents = false
            if (shouldRestoreBubble && Settings.canDrawOverlays(context) && automationSuppressionDepth == 0 && !bubbleOverlay.isVisible) {
                show()
            }
        }
    }

    fun setHostConnectionState(state: HostConnectionState) {
        lastHostConnectionState = state
        mainHandler.post { renderHostConnectionState(state) }
    }

    fun setVoiceState(state: VoiceRuntimeState) {
        lastVoiceState = state
        mainHandler.post { renderVoiceState(state) }
    }

    fun setChatState(state: ChatState) {
        lastChatState = state
        val sequence = ++chatRenderSequence
        mainHandler.post {
            if (sequence < chatRenderSequence) {
                return@post
            }
            renderChatState(state)
            state.status?.let { setStatus(it) }
            state.error?.let { setStatus(it) }
            notifyCurrentChatSessionViewed()
        }
    }

    fun isViewingChatSession(sessionKey: String?): Boolean {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return false
        return isPanelActivelyViewed() && lastChatState.sessionKey == key
    }

    private fun isPanelActivelyViewed(): Boolean {
        return panelView != null && panelHasWindowFocus
    }

    fun isBubbleVisible(): Boolean {
        return bubbleOverlay.isVisible
    }

    /**
     * Rebuilds the bubble's inner avatar view in-place so changes from the
     * Avatar picker take effect immediately, without waiting for the bubble
     * to be torn down and re-shown.
     */
    fun refreshBubbleAvatar() {
        mainHandler.post {
            bubbleOverlay.refreshAvatar(lastChatState)
        }
    }

    /**
     * Resize the floating bubble in place. Used by the live-preview slider
     * in the Appearance dialog. Coerces into [MIN_BUBBLE_SIZE_DP,
     * MAX_BUBBLE_SIZE_DP] and keeps the bubble inside the screen if it grew
     * near an edge.
     */
    fun refreshBubbleSize(targetDp: Int) {
        mainHandler.post {
            bubbleOverlay.refreshSize(targetDp)
        }
    }

    fun openChatPanel(
        markCurrentSessionViewed: Boolean = true,
        presentation: PanelPresentation = PanelPresentation.Popup
    ) {
        mainHandler.post {
            if (panelView == null) {
                if (!bubbleOverlay.isVisible && presentation == PanelPresentation.Popup) {
                    show()
                }
                suppressNextPanelViewedCallback = !markCurrentSessionViewed
                togglePanel(presentation)
            } else {
                if (activePanelPresentation != presentation) {
                    suppressNextPanelViewedCallback = !markCurrentSessionViewed
                    dismissPanel(force = true)
                    togglePanel(presentation)
                    return@post
                }
                if (markCurrentSessionViewed) {
                    notifyCurrentChatSessionViewed()
                }
            }
        }
    }

    fun setTranscriptionState(state: VoiceTranscriptionState) {
        lastTranscriptionState = state
        mainHandler.post { renderTranscriptionState(state) }
    }

    fun insertComposerTranscript(transcript: String) {
        val normalized = transcript.trim()
        if (normalized.isBlank()) {
            return
        }
        mainHandler.post {
            val input = composerInput
            if (input == null) {
                return@post
            }
            val existing = input.text.toString()
            val separator = when {
                existing.isBlank() -> ""
                existing.endsWith("\n") -> ""
                else -> "\n"
            }
            val next = existing + separator + normalized
            input.setText(next)
            input.setSelection(next.length)
            setStatus("Transcript added to composer for review.")
        }
    }

    fun addChatAttachment(attachment: StoredChatAttachment) {
        mainHandler.post {
            attachmentTray.add(attachment)
            setStatus("Attached ${attachment.displayName}")
        }
    }

    fun askConfirmation(message: String, preview: String?): CompletableDeferred<Boolean> {
        return confirmationOverlay.ask(message, preview)
    }

    fun dismissConfirmation() {
        confirmationOverlay.dismiss()
    }

    fun openPanel(presentation: PanelPresentation = PanelPresentation.Popup) {
        mainHandler.post {
            if (presentation == PanelPresentation.Shell) {
                return@post
            }
            if (panelView != null) {
                if (activePanelPresentation == presentation) {
                    notifyCurrentChatSessionViewed()
                    return@post
                }
                dismissPanel(force = true)
            }
            presentPanel(presentation)
        }
    }

    fun attachShellChat(container: FrameLayout) {
        mainHandler.post {
            if (panelView != null && activePanelPresentation == PanelPresentation.Shell) {
                val currentParent = panelView?.parent as? ViewGroup
                if (currentParent === container && container.childCount > 0) {
                    suppressBubbleForShellChat()
                    notifyCurrentChatSessionViewed()
                    return@post
                }
                dismissPanel(force = true)
            } else if (panelView != null) {
                dismissPanel(force = true)
            }
            suppressBubbleForShellChat()
            presentPanel(PanelPresentation.Shell, container)
        }
    }

    fun detachShellChat() {
        mainHandler.post {
            if (activePanelPresentation == PanelPresentation.Shell) {
                dismissPanel(force = true)
                restoreBubbleAfterShellChatDismiss()
            }
        }
    }

    fun consumeShellBackPress(): Boolean {
        if (activePanelPresentation != PanelPresentation.Shell) {
            return false
        }
        return dismissActiveDropdown()
    }

    private fun togglePanel(presentation: PanelPresentation = PanelPresentation.Popup) {
        if (panelView != null) {
            dismissPanel()
            return
        }
        presentPanel(presentation)
        notifyCurrentChatSessionOpened()
    }

    private fun presentPanel(
        presentation: PanelPresentation,
        shellContainer: FrameLayout? = null
    ) {
        activePanelPresentation = presentation
        if (presentation == PanelPresentation.Fullscreen) {
            suppressBubbleForFullscreen()
        }
        val tokens = tokens()
        val input = buildComposerInput(tokens)
        val status = StatusUpdateView(context, tokens).apply {
            setText(chatStatusText(lastChatState.status, lastChatState))
            setActive(lastChatState.isRunning)
        }
        statusText = status
        val voice = voicePanel.build(tokens)
        val composer = buildComposer(tokens, input)
        val header = buildModalHeader(tokens, presentation)

        val display = context.resources.displayMetrics
        val shellHeight = shellContainer?.height?.takeIf { it > 0 }
        val defaultBounds = panelDefaultBounds(
            displayHeight = display.heightPixels,
            presentation = presentation,
            shellHeight = shellHeight
        )
        val dismissOnBack = presentation != PanelPresentation.Shell
        val handle = panelChrome.build(
            tokens = tokens,
            presentation = presentation,
            header = header,
            voice = voice,
            status = status,
            composer = composer,
            defaultBounds = defaultBounds,
            dismissOnBack = dismissOnBack
        )
        val host = handle.host
        val params = handle.panelParams
        val scrim = handle.scrim
        panelHost = host
        panelContent = handle.content
        keyboardSpacerView = handle.keyboardSpacer
        chatTimelineBinder.bind(handle.historyContainer, handle.historyScrollView)
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                armKeyboardFallback()
                if (presentation == PanelPresentation.Shell) {
                    mainHandler.postDelayed({ positionPanelAboveKeyboard(host, null) }, 300)
                    mainHandler.postDelayed({ positionPanelAboveKeyboard(host, null) }, 700)
                } else {
                    mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 300)
                    mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 700)
                }
            } else if (presentation != PanelPresentation.Shell) {
                restorePanelDefaultSize(host, params)
            }
        }

        if (presentation == PanelPresentation.Shell && shellContainer != null) {
            shellContainer.removeAllViews()
            shellContainer.addView(
                host,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            host.viewTreeObserver.addOnGlobalLayoutListener { positionPanelAboveKeyboard(host, null) }
            panelView = host
            panelParams = null
            panelScrimView = null
            panelScrimParams = null
        } else {
            windowManager.addView(scrim, handle.scrimParams)
            panelScrimView = scrim
            panelScrimParams = handle.scrimParams

            windowManager.addView(host, params)
            host.viewTreeObserver.addOnGlobalLayoutListener { positionPanelAboveKeyboard(host, params) }
            scrim.viewTreeObserver.addOnGlobalLayoutListener { positionPanelAboveKeyboard(host, params) }
            panelView = host
            panelParams = params
        }

        host.requestFocus()
        panelHasWindowFocus = true

        renderChatState(lastChatState)
        renderVoiceState(lastVoiceState)
        renderHostConnectionState(lastHostConnectionState)
        renderTranscriptionState(lastTranscriptionState)

        if (presentation == PanelPresentation.Shell) {
            host.alpha = 1f
        } else {
            runPanelOpenAnimation(host, scrim, appearancePrefs(), handle.defaultHeight)
        }
        if (suppressNextPanelViewedCallback) {
            suppressNextPanelViewedCallback = false
        } else {
            notifyCurrentChatSessionViewed()
        }
    }

    private fun buildModalHeader(tokens: ThemeTokens, presentation: PanelPresentation): View {
        val newChatButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_new_chat,
            contentDescription = "Start new chat",
            viewId = R.id.openclaw_new_chat_button,
            compact = true
        ) { startNewChatSession() }
        val connectionButton = HostConnectionIndicatorButton(context).apply {
            id = R.id.openclaw_header_host_status_button
            bind(tokens, lastHostConnectionState)
            setOnClickListener { showHostConnectionPopup(this) }
        }
        connectionIndicatorButton = connectionButton
        val voiceButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_voice_wave,
            contentDescription = "Start realtime voice mode",
            viewId = R.id.openclaw_header_voice_button,
            compact = true
        ) {
            startVoiceAndMinimizePanel()
        }
        val settingsButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_settings_gear,
            contentDescription = "Lynk settings",
            viewId = R.id.openclaw_header_settings_button,
            compact = true
        ) {
            if (presentation == PanelPresentation.Shell) {
                openSettings()
            } else {
                dismissPanel()
                openSettings()
            }
        }
        val closeButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_close,
            contentDescription = "Close chat",
            viewId = R.id.openclaw_header_close_button,
            compact = true
        ) { handlePanelBackPressed() }

        val handleArea = if (presentation == PanelPresentation.Popup) {
            val handle = View(context).apply {
                exposeToAccessibility(
                    viewId = R.id.openclaw_panel_drag_handle,
                    description = "Drag down to dismiss chat"
                )
                background = Drawables.rounded(
                    fill = DesignTokens.withAlpha(tokens.tertiaryText, 0x80),
                    radius = dp(DesignTokens.Radius.pill).toFloat()
                )
            }
            FrameLayout(context).apply {
                addView(handle, FrameLayout.LayoutParams(
                    dp(34),
                    dp(4)
                ).apply { gravity = Gravity.CENTER })
                attachSwipeToDismiss(this)
            }
        } else {
            null
        }

        val titleLogoSize = dp(25)
        val titleChevronSize = dp(17)
        val titleChevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(tokens.secondaryText)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            hideFromAccessibility()
            headerSessionChevron = this
        }
        val titleStack = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            contentDescription = "Open chat menu"
            setPadding(dp(6), dp(3), dp(6), dp(3))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.openclaw_bubble_logo)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                hideFromAccessibility()
                headerBrandLogo = this
            }, LinearLayout.LayoutParams(titleLogoSize, titleLogoSize).apply {
                rightMargin = dp(DesignTokens.Spacing.xs)
            })
            addView(TextView(context).apply {
                text = ChatPresentationHelpers.headerTitleText(brandPresentationFor(lastChatState), tokens)
                textSize = 17f
                setTextColor(tokens.primaryText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                isSingleLine = true
                maxWidth = dp(128)
                ellipsize = android.text.TextUtils.TruncateAt.END
                hideFromAccessibility()
                headerBrandTitle = this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(titleChevron, LinearLayout.LayoutParams(titleChevronSize, titleChevronSize).apply {
                leftMargin = dp(DesignTokens.Spacing.xs)
            })
            exposeToAccessibility(
                viewId = R.id.openclaw_header_menu_button,
                description = "Open chat menu",
                focusable = true
            )
            setOnClickListener { showHeaderChatMenu() }
            headerSessionAnchor = this
        }

        val headerSize = (dp(DesignTokens.Sizes.compact) * 1.1f).roundToInt()
        val headerGap = (dp(3) * 1.1f).roundToInt()
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(newChatButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(connectionButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(voiceButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(settingsButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            if (presentation != PanelPresentation.Shell) {
                addView(closeButton, LinearLayout.LayoutParams(headerSize, headerSize))
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.xs))
            handleArea?.let {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(20)
                ).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(DesignTokens.Spacing.xs), 0, dp(DesignTokens.Spacing.xs))
                addView(titleStack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(DesignTokens.Spacing.xs)
                })
                addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(actions)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun attachSwipeToDismiss(target: View) {
        val threshold = dp(38).toFloat()
        target.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var tracking = false
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        tracking = true
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!tracking) return false
                        val dy = event.rawY - startY
                        if (dy > threshold) {
                            tracking = false
                            dismissPanel()
                            return true
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startVoiceAndMinimizePanel() {
        if (lastTranscriptionState.isRecording) {
            onCancelTranscription()
        }
        anchoredPicker?.dismiss()
        anchoredPicker = null
        val shouldMinimizeHostApp = shouldMinimizeHostAppAfterVoiceStart(activePanelPresentation)
        if (!onStartVoice()) {
            return
        }
        panelView?.animate()?.cancel()
        panelScrimView?.animate()?.cancel()
        finalizePanelDismiss()
        onRevealVoicePet()
        if (shouldMinimizeHostApp) {
            onMinimizeHostApp()
        }
    }

    private fun handlePanelBackPressed(): Boolean {
        if (dismissActiveDropdown()) {
            return true
        }
        if (activePanelPresentation == PanelPresentation.Shell) {
            return false
        }
        dismissPanel()
        return true
    }

    private fun dismissActiveDropdown(): Boolean {
        if (connectionPopupView != null) {
            dismissHostConnectionPopup()
            return true
        }
        if (isAnchoredPickerShowing()) {
            anchoredPicker?.dismiss()
            return true
        }
        return false
    }

    private fun isAnchoredPickerShowing(): Boolean {
        return anchoredPicker?.isShowing == true
    }

    private fun clearComposerDraft(input: EditText? = composerInput) {
        composerDraftText = ""
        input?.setText("")
    }

    private fun buildComposerInput(tokens: ThemeTokens): EditText {
        return object : EditText(context) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    suppressKeyboardFallback()
                    panelView?.let { panel ->
                        panelParams?.let { params -> restorePanelDefaultSize(panel, params) }
                    }
                    if (event.action == KeyEvent.ACTION_UP) {
                        clearFocus()
                    }
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_composer_input,
                description = "Message composer"
            )
            hint = brandPresentationFor(lastChatState).copy.composerPlaceholder
            minLines = 1
            maxLines = 5
            minHeight = dp(DesignTokens.Sizes.compactAction)
            textSize = DesignTokens.Text.body
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            background = null
            backgroundTintList = null
            includeFontPadding = false
            setPadding(dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm))
            setOnClickListener {
                armKeyboardFallback()
                panelView?.let { panel ->
                    panelParams?.let { params ->
                        mainHandler.postDelayed({ keepAboveKeyboard(panel, params) }, 300)
                    }
                }
                maybeShowCommandAutocomplete(this)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    composerDraftText = s?.toString().orEmpty()
                    if (!suppressComposerAutocomplete) {
                        maybeShowCommandAutocomplete(this@apply)
                    }
                    renderComposerActionButtons(tokens(), lastChatState, lastTranscriptionState)
                }
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressComposerAutocomplete) {
                        applySkillHighlights(this@apply)
                    }
                }
            })
            if (composerDraftText.isNotEmpty()) {
                setText(composerDraftText)
                setSelection(text?.length ?: 0)
            }
            composerInput = this
        }
    }

    private fun buildComposer(tokens: ThemeTokens, input: EditText): LinearLayout {
        val inputCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.glassInset(context, tokens, DesignTokens.Radius.lg)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(attachmentTray.build(tokens), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val controlSize = dp(DesignTokens.Sizes.compact)
        val sendSize = dp(DesignTokens.Sizes.compactAction)
        val controlGap = dp(3)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menuButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_plus,
            contentDescription = "Open chat menu",
            viewId = R.id.openclaw_composer_menu_button,
            compact = true,
            onClick = {}
        ).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        menuButton.setOnClickListener { showPlusMenu(anchorOverride = menuButton) }
        controls.addView(menuButton, LinearLayout.LayoutParams(controlSize, controlSize).apply { rightMargin = controlGap })

        modelButton = compactPill(tokens, "Model", R.drawable.ic_model).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_model_selector,
                description = "Model selector",
                stateDescription = text
            )
            ellipsize = null
            setHorizontallyScrolling(false)
            setOnClickListener { showModelChoices() }
        }
        controls.addView(modelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            controlSize
        ).apply {
            rightMargin = controlGap
        })

        reasoningButton = compactPill(tokens, "Reason", R.drawable.ic_reasoning).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_reasoning_selector,
                description = "Reasoning selector",
                stateDescription = text
            )
            ellipsize = null
            setHorizontallyScrolling(false)
            setOnClickListener { showReasoningChoices() }
            setOnLongClickListener { cycleReasoningChoice(); true }
        }
        controls.addView(reasoningButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            controlSize
        ).apply {
            rightMargin = controlGap
        })

        contextUsageView = ContextUsageView(context).apply {
            id = R.id.openclaw_context_usage_button
            bind(tokens, lastChatState.usage.contextRatio)
            setOnClickListener { showUsageControls() }
        }
        controls.addView(contextUsageView, LinearLayout.LayoutParams(controlSize, controlSize).apply { rightMargin = controlGap })

        controls.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))

        transcriptionMicButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_mic,
            contentDescription = "Start voice transcription",
            viewId = R.id.openclaw_transcription_button
        ) {
            if (lastTranscriptionState.isRecording) {
                onCancelTranscription()
            } else {
                onStartTranscription()
            }
        }
        controls.addView(transcriptionMicButton, LinearLayout.LayoutParams(sendSize, sendSize).apply { rightMargin = dp(DesignTokens.Spacing.sm) })

        sendStopButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_send,
            contentDescription = "Send message",
            viewId = R.id.openclaw_send_stop_button,
            accent = true
        ) {
            val text = input.text.toString().trim()
            val attachments = attachmentTray.snapshot()
            if (lastTranscriptionState.isRecording) {
                onStopTranscription()
                setStatus("Transcribing audio...")
            } else if (text.isNotEmpty() || attachments.isNotEmpty()) {
                if (onSubmit(text, attachments)) {
                    clearComposerDraft(input)
                    attachmentTray.clear()
                    setStatus(brandPresentationFor(lastChatState).copy.sentStatus)
                }
            } else if (lastChatState.isRunning) {
                onStop()
                setStatus("Stop requested")
            }
        }
        controls.addView(sendStopButton, LinearLayout.LayoutParams(sendSize, sendSize))

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            composerContainer = this
            exposeToAccessibility(
                viewId = R.id.openclaw_composer_container,
                description = "Message composer controls"
            )
            addView(inputCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(DesignTokens.Spacing.sm) })
        }
    }

    private fun iconButton(
        tokens: ThemeTokens,
        drawableRes: Int,
        contentDescription: String,
        viewId: Int? = null,
        accent: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit
    ): ImageButton {
        val pad = if (compact) 6 else DesignTokens.Spacing.sm
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            background = if (accent) Drawables.accentSurface(context, tokens, DesignTokens.Radius.pill)
                else Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            setColorFilter(if (accent) tokens.accentInk else tokens.primaryText)
            exposeToAccessibility(
                viewId = viewId,
                description = contentDescription,
                focusable = true
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setMinimumWidth(0)
            setMinimumHeight(0)
            adjustViewBounds = true
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            setOnClickListener { onClick() }
        }
    }

    private fun compactPill(tokens: ThemeTokens, label: String, iconRes: Int): TextView {
        return TextView(context).apply {
            text = label
            textSize = DesignTokens.Text.caption
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(96)
            setTextColor(tokens.primaryText)
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            minWidth = dp(54)
            minHeight = dp(DesignTokens.Sizes.compact)
            includeFontPadding = false
            setPadding(dp(DesignTokens.Spacing.sm + 2), 0, dp(DesignTokens.Spacing.sm + 2), 0)
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, R.drawable.ic_chevron_down, 0)
            compoundDrawablePadding = dp(3)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tokens.secondaryText)
        }
    }

    private fun ensurePicker(): AnchoredPicker {
        val existing = anchoredPicker
        if (existing != null) return existing
        val tokens = tokens()
        val created = AnchoredPicker(context, tokens)
        anchoredPicker = created
        return created
    }

    private fun showAnchoredPicker(
        anchor: View,
        title: String,
        sections: List<AnchoredPicker.Section>,
        toggleSameAnchor: Boolean = true,
        replaceShowing: Boolean = false,
        heightFraction: Float? = null,
        preferAbove: Boolean = false,
        revealRowId: String? = null,
        revealRowVerticalBias: Float? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val host = panelHost ?: return
        val shouldPreferAbove = preferAbove || anchor === composerInput
        fun showNow() {
            if (panelHost !== host || !host.isAttachedToWindow || !anchor.isAttachedToWindow) return
            val picker = ensurePicker()
            if (replaceShowing && picker.isShowingFor(anchor)) {
                picker.update(
                    title = title,
                    sections = sections,
                    heightFraction = heightFraction,
                    preferAbove = shouldPreferAbove,
                    revealRowId = revealRowId,
                    revealRowVerticalBias = revealRowVerticalBias
                )
                return
            }
            if (toggleSameAnchor && picker.isShowingFor(anchor)) {
                picker.dismiss()
                return
            }
            picker.show(
                host = host,
                anchor = anchor,
                title = title,
                sections = sections,
                heightFraction = heightFraction,
                preferAbove = shouldPreferAbove,
                onDismiss = onDismiss
            )
        }

        if (deferPickerUntilKeyboardDismissed(host, anchor) { showNow() }) {
            return
        }
        if (anchor !== composerInput) hideComposerKeyboard()
        showNow()
    }

    private fun hideComposerKeyboard() {
        val input = composerInput ?: return
        suppressKeyboardFallback()
        input.clearFocus()
        panelView?.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun deferPickerUntilKeyboardDismissed(
        host: FrameLayout,
        anchor: View,
        onReady: () -> Unit
    ): Boolean {
        if (anchor === composerInput) return false
        val panel = panelView ?: return false
        if (!shouldDelayPickerForKeyboard(panel)) return false

        hideComposerKeyboard()
        pendingPickerShowRunnable?.let { mainHandler.removeCallbacks(it) }

        val startedAt = System.currentTimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (panelHost !== host || !host.isAttachedToWindow || !anchor.isAttachedToWindow) {
                    clearPendingPickerShow(this)
                    return
                }

                val timedOut = System.currentTimeMillis() - startedAt >= PICKER_KEYBOARD_DISMISS_TIMEOUT_MS
                if (!timedOut && isKeyboardVisibleForPicker(panel)) {
                    mainHandler.postDelayed(this, PICKER_KEYBOARD_DISMISS_POLL_MS)
                    return
                }

                restorePanelForPicker()
                host.post {
                    if (panelHost === host && host.isAttachedToWindow && anchor.isAttachedToWindow) {
                        onReady()
                    }
                }
                clearPendingPickerShow(this)
            }
        }

        pendingPickerShowRunnable = task
        mainHandler.postDelayed(task, PICKER_KEYBOARD_DISMISS_INITIAL_DELAY_MS)
        return true
    }

    private fun clearPendingPickerShow(task: Runnable) {
        if (pendingPickerShowRunnable === task) {
            pendingPickerShowRunnable = null
        }
    }

    private fun shouldDelayPickerForKeyboard(panel: View): Boolean {
        return composerInput?.hasFocus() == true ||
            isKeyboardVisibleForPicker(panel) ||
            isPanelAdjustedForKeyboard(panel)
    }

    private fun isKeyboardVisibleForPicker(panel: View): Boolean {
        val displayHeight = context.resources.displayMetrics.heightPixels
        val defaultBounds = panelDefaultBounds(displayHeight)
        val imeHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            panel.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            0
        }
        return imeHeight >= dp(120) || keyboardTopFromVisibleFrame(defaultBounds.y + defaultBounds.height) != null
    }

    private fun isPanelAdjustedForKeyboard(panel: View): Boolean {
        val spacerHeight = keyboardSpacerView
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.layoutParams
            ?.height
            ?: 0
        if (spacerHeight > 0) return true

        val params = panelParams ?: return false
        val displayHeight = context.resources.displayMetrics.heightPixels
        val defaultBounds = panelDefaultBounds(displayHeight)
        return params.height != defaultBounds.height || params.y != defaultBounds.y || panel.translationY != 0f
    }

    private fun restorePanelForPicker() {
        val panel = panelView ?: return
        val params = panelParams
        if (params != null) {
            restorePanelDefaultSize(panel, params)
        } else {
            panel.translationY = 0f
            setKeyboardSpacerHeight(0)
            anchoredPicker?.reposition()
        }
    }

    private fun renderHostConnectionState(state: HostConnectionState) {
        connectionIndicatorButton?.bind(tokens(), state)
    }

    private fun renderHeaderBrand(tokens: ThemeTokens, state: ChatState) {
        val presentation = brandPresentationFor(state)
        headerBrandLogo?.setImageResource(presentation.logoRes)
        headerBrandTitle?.apply {
            text = ChatPresentationHelpers.headerTitleText(presentation, tokens)
            setTextColor(ChatPresentationHelpers.headerTitleColor(presentation, tokens))
        }
        headerSessionAnchor?.apply {
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            contentDescription = "Open ${presentation.title} chat menu"
        }
    }

    private fun brandPresentationFor(state: ChatState): dev.androidagent.overlay.ClientBrandPresentation {
        val config = AgentConfigStore.load(context)
        val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
        val modelOptions = ChatPresentationHelpers.modelPickerOptions(
            state,
            localLiteRtAvailable,
            config.enabledModelHarnessIds()
        )
        return ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = ChatPresentationHelpers.selectedModelId(state.selectedModel, localLiteRtAvailable, modelOptions),
            models = modelOptions,
            harnessId = state.harnessId?.takeIf { config.isModelHarnessEnabled(it) },
            localLiteRtAvailable = localLiteRtAvailable
        )
    }

    private fun chatStatusText(rawStatus: String?, state: ChatState): String {
        return ChatPresentationHelpers.chatStatusText(
            rawStatus = rawStatus,
            isRunning = state.isRunning,
            presentation = brandPresentationFor(state)
        )
    }

    private fun showHostConnectionPopup(anchor: View) {
        val host = panelHost ?: return
        if (connectionPopupView != null) {
            dismissHostConnectionPopup()
            return
        }
        anchoredPicker?.dismiss()

        val tokens = tokens()
        val state = lastHostConnectionState
        val statusColor = hostConnectionColor(tokens, state.phase)
        val scrim = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismissHostConnectionPopup() }
        }
        host.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        connectionPopupScrimView = scrim

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.dropdownSheet(context, tokens)
            elevation = dp(DesignTokens.Elevation.popover).toFloat()
            isClickable = true
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md)
            )
        }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = Drawables.circle(statusColor)
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(DesignTokens.Spacing.sm)
            })
            addView(TextView(context).apply {
                text = HostConnectionCopy.title(state.phase)
                Typography.applyCallout(this, tokens)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(statusColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        card.addView(TextView(context).apply {
            text = HostConnectionCopy.message(state)
            Typography.applyFootnote(this, tokens, secondary = state.phase != HostConnectionPhase.ERROR)
            if (state.phase == HostConnectionPhase.ERROR) {
                setTextColor(tokens.danger)
            }
            setPadding(0, dp(DesignTokens.Spacing.sm), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        })

        val width = (context.resources.displayMetrics.widthPixels - dp(DesignTokens.Spacing.xxl * 2))
            .coerceAtMost(dp(300))
        val params = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(DesignTokens.Spacing.md)
            topMargin = dp(DesignTokens.Spacing.md)
        }
        host.addView(card, params)
        connectionPopupView = card
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.post {
            positionHostConnectionPopup(host, anchor, card)
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
        }
    }

    private fun dismissHostConnectionPopup() {
        val popup = connectionPopupView
        val scrim = connectionPopupScrimView
        connectionPopupView = null
        connectionPopupScrimView = null
        scrim?.let { (it.parent as? FrameLayout)?.removeView(it) }
        popup?.animate()
            ?.alpha(0f)
            ?.scaleX(0.96f)
            ?.scaleY(0.96f)
            ?.setDuration(110L)
            ?.withEndAction { (popup.parent as? FrameLayout)?.removeView(popup) }
            ?.start()
    }

    private fun positionHostConnectionPopup(host: FrameLayout, anchor: View, popup: View) {
        val hostLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        anchor.getLocationOnScreen(anchorLocation)

        val sideMargin = dp(DesignTokens.Spacing.md)
        val gap = dp(DesignTokens.Spacing.sm)
        val anchorLeft = anchorLocation[0] - hostLocation[0]
        val anchorTop = anchorLocation[1] - hostLocation[1]
        val anchorBottom = anchorTop + anchor.height
        val anchorCenterX = anchorLeft + anchor.width / 2
        val popupWidth = popup.measuredWidth.coerceAtLeast(dp(220))
        val popupHeight = popup.measuredHeight.coerceAtLeast(dp(88))

        val params = popup.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = (anchorCenterX - popupWidth / 2)
            .coerceAtLeast(sideMargin)
            .coerceAtMost(host.width - popupWidth - sideMargin)
            .coerceAtLeast(sideMargin)
        params.topMargin = if (anchorBottom + gap + popupHeight + sideMargin > host.height && anchorTop > popupHeight + gap) {
            anchorTop - popupHeight - gap
        } else {
            anchorBottom + gap
        }
        popup.layoutParams = params
        popup.pivotX = (anchorCenterX - params.leftMargin).toFloat().coerceIn(0f, popupWidth.toFloat())
        popup.pivotY = if (params.topMargin < anchorTop) popupHeight.toFloat() else 0f
    }

    private fun showModelChoices(
        anchorOverride: View? = null,
        replace: Boolean = false,
        revealFirstModelForHarnessId: String? = null
    ) {
        val anchor = anchorOverride ?: modelButton ?: return
        val config = AgentConfigStore.load(context)
        val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
        val merged = availableModelOptions(config, localLiteRtAvailable)
        if (merged.isEmpty()) {
            setStatus("No models available.")
            return
        }
        val selectedId = selectedModelId(merged, localLiteRtAvailable)
        val activeHarnessId = activeHarnessId(merged, localLiteRtAvailable)
        if (modelPickerActiveHarnessId != activeHarnessId) {
            expandedModelHarnesses.clear()
            modelPickerActiveHarnessId = activeHarnessId
            expandedModelHarnesses.add(activeHarnessId)
        }

        val groups = ChatPresentationHelpers.harnessModelGroups(merged, config.enabledModelHarnessIds())

        val sections = groups.map { group ->
            val expanded = group.id in expandedModelHarnesses
            val isActiveHarness = group.id == activeHarnessId
            val harnessRow = AnchoredPicker.Row(
                id = "model-harness:${group.id}",
                label = group.label,
                sublabel = if (isActiveHarness) "Active harness" else "${group.models.size} model${if (group.models.size == 1) "" else "s"}",
                selected = false,
                selectable = false,
                emphasizeSublabel = isActiveHarness,
                trailingIconRes = R.drawable.ic_chevron_right,
                trailingIconRotation = if (expanded) 90f else 0f,
                dismissOnSelect = false,
                onSelect = {
                    if (expanded) {
                        expandedModelHarnesses.remove(group.id)
                    } else {
                        expandedModelHarnesses.add(group.id)
                    }
                    showModelChoices(
                        replace = true,
                        revealFirstModelForHarnessId = if (expanded) null else group.id
                    )
                }
            )
            val rows = if (!expanded) {
                listOf(harnessRow)
            } else {
                listOf(harnessRow) + group.models.map { model ->
                    AnchoredPicker.Row(
                        id = "model:${model.id}",
                        label = model.label,
                        sublabel = ChatPresentationHelpers.modelProviderSublabel(model, group.label),
                        iconRes = R.drawable.ic_model,
                        selected = model.id == selectedId,
                        enabled = model.available != false,
                        onSelect = {
                            onSetChatModel(model.id)
                            setStatus("Model: ${group.label} / ${model.label}")
                        }
                    )
                }
            }
            AnchoredPicker.Section(null, rows)
        }
        val hasExpandedHarness = groups.any { group -> group.id in expandedModelHarnesses }
        val firstModelRevealRowId = revealFirstModelForHarnessId
            ?.let { harnessId ->
                groups.firstOrNull { group -> group.id == harnessId }
                    ?.models
                    ?.firstOrNull()
                    ?.id
            }
            ?.let { modelId -> "model:$modelId" }
        showAnchoredPicker(
            anchor = anchor,
            title = "Model",
            sections = sections,
            toggleSameAnchor = !replace,
            replaceShowing = replace,
            heightFraction = if (hasExpandedHarness) 0.65f else null,
            revealRowId = firstModelRevealRowId
        )
    }

    private fun showHarnessChoices(anchorOverride: View? = null, replace: Boolean = false) {
        val anchor = anchorOverride ?: headerSessionAnchor ?: panelContent ?: panelHost ?: return
        val config = AgentConfigStore.load(context)
        val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
        val merged = availableModelOptions(config, localLiteRtAvailable)
        val groups = ChatPresentationHelpers.harnessModelGroups(merged, config.enabledModelHarnessIds())
        if (groups.size < 2) {
            setStatus("Only one harness is available.")
            return
        }

        val rows = ChatPickerRows.harnessRows(
            state = lastChatState,
            groups = groups,
            activeHarnessId = activeHarnessId(merged, localLiteRtAvailable)
        ) { group ->
            onSetChatHarness(group.id)
            setStatus("Harness: ${group.label}")
        }

        showAnchoredPicker(
            anchor = anchor,
            title = "Harness",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = !replace,
            replaceShowing = replace
        )
    }

    private fun availableModelOptions(
        config: AgentConfig = AgentConfigStore.load(context),
        localLiteRtAvailable: Boolean = isExperimentalLocalModelAvailable(config)
    ): List<ChatModelOption> {
        return ChatPresentationHelpers.modelPickerOptions(
            lastChatState,
            localLiteRtAvailable,
            config.enabledModelHarnessIds()
        )
    }

    private fun selectedModelId(models: List<ChatModelOption>, localLiteRtAvailable: Boolean): String {
        return ChatPresentationHelpers.selectedModelId(lastChatState.selectedModel, localLiteRtAvailable, models)
    }

    private fun activeHarnessId(models: List<ChatModelOption>, localLiteRtAvailable: Boolean): String {
        val selectedId = selectedModelId(models, localLiteRtAvailable)
        return models.firstOrNull { it.id == selectedId }?.let { ChatPresentationHelpers.modelHarnessId(it) }
            ?: lastChatState.harnessId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: ChatModelCatalog.harnessFromSessionKey(lastChatState.sessionKey)
            ?: AgentConfig.HARNESS_OPENCLAW
    }

    private fun isExperimentalLocalModelAvailable(config: AgentConfig = AgentConfigStore.load(context)): Boolean {
        return config.experimentalLocalModelsEnabled && LocalModelStore.exists(config.localModelPath)
    }

    private fun showReasoningChoices(anchorOverride: View? = null, replace: Boolean = false) {
        val anchor = anchorOverride ?: reasoningButton ?: return
        val options = lastChatState.reasoningOptions.ifEmpty { ChatState.defaultReasoningOptions }
        val rows = options.map { option ->
            AnchoredPicker.Row(
                id = "reasoning:${option.id}",
                label = option.label,
                iconRes = R.drawable.ic_reasoning,
                selected = option.id == (lastChatState.reasoningEffort ?: ""),
                onSelect = {
                    onSetChatReasoning(option.id)
                    setStatus("Reasoning: ${option.label}")
                }
            )
        }
        showAnchoredPicker(
            anchor = anchor,
            title = "Reasoning",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = !replace,
            replaceShowing = replace
        )
    }

    private fun showUploadFileChoices(anchorOverride: View? = null, replace: Boolean = false) {
        val anchor = anchorOverride ?: headerSessionAnchor ?: panelContent ?: panelHost ?: return
        val rows = listOf(
            AnchoredPicker.Row(
                id = "upload:image",
                label = "Image",
                sublabel = "Choose any image file",
                iconRes = R.drawable.ic_file,
                onSelect = {
                    onPickChatAttachment(ChatAttachmentKind.IMAGE)
                    setStatus("Choose an image to attach")
                }
            ),
            AnchoredPicker.Row(
                id = "upload:file",
                label = "File",
                sublabel = "Choose any file",
                iconRes = R.drawable.ic_file,
                onSelect = {
                    onPickChatAttachment(ChatAttachmentKind.FILE)
                    setStatus("Choose a file to attach")
                }
            )
        )
        showAnchoredPicker(
            anchor = anchor,
            title = "Upload File",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = !replace,
            replaceShowing = replace,
            preferAbove = true
        )
    }

    private fun cycleReasoningChoice() {
        val options = lastChatState.reasoningOptions.ifEmpty { ChatState.defaultReasoningOptions }
        val current = lastChatState.reasoningEffort
        val nextIndex = ((options.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: -1) + 1) % options.size
        val next = options[nextIndex]
        onSetChatReasoning(next.id)
        setStatus("Reasoning: ${next.label}")
    }

    private fun showHeaderChatMenu() {
        val anchor = headerSessionAnchor ?: panelHost ?: return
        if (anchoredPicker?.isShowingFor(anchor) != true) {
            animateHeaderSessionChevron(expanded = true)
        }
        showPlusMenu(
            anchorOverride = anchor,
            onDismiss = { animateHeaderSessionChevron(expanded = false) }
        )
    }

    private fun animateHeaderSessionChevron(expanded: Boolean) {
        headerSessionChevron?.animate()
            ?.rotation(if (expanded) 180f else 0f)
            ?.setDuration(160L)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
    }

    private fun sessionPickerRows(
        sessions: List<ChatSessionRow> = lastChatState.sessions,
        limit: Int = 30
    ): List<AnchoredPicker.Row> {
        return ChatPickerRows.sessionRows(lastChatState, sessions, limit, onSelectChatSession)
    }

    private fun sessionPickerSections(limit: Int = Int.MAX_VALUE): List<AnchoredPicker.Section> {
        if (!isWorkspaceHarness()) {
            return listOf(AnchoredPicker.Section(null, sessionPickerRows(limit = 30)))
        }
        val sessions = workspaceSessionsForActiveHarness()
        syncExpandedSessionWorkspace()
        return WorkspaceSessionPickerSections.build(
            sessions = sessions,
            selectedSessionKey = lastChatState.sessionKey,
            activeWorkspacePath = activeWorkspacePathForSessionPicker(),
            expandedWorkspaceKeys = expandedSessionWorkspaces,
            expandedQuickChats = expandedSessionQuickChats,
            unreadCountForSession = lastChatState::unreadCountForSession,
            onToggleWorkspace = { workspaceKey -> toggleSessionWorkspace(workspaceKey) },
            onToggleQuickChats = { toggleSessionQuickChats() },
            onSelectSession = onSelectChatSession,
            limit = limit
        )
    }

    private fun syncExpandedSessionWorkspace() {
        val sessions = workspaceSessionsForActiveHarness()
        val activeWorkspaceKey = WorkspaceSessionPickerSections.activeWorkspaceKey(
            sessions = sessions,
            selectedSessionKey = lastChatState.sessionKey,
            activeWorkspacePath = activeWorkspacePathForSessionPicker()
        )
        val activeGroupKey = activeWorkspaceKey?.let { "workspace:$it" }
            ?: if (WorkspaceSessionPickerSections.isQuickChatSession(sessions, lastChatState.sessionKey)) {
                "quick-chats"
            } else {
                null
            }
        if (sessionPickerActiveGroupKey != activeGroupKey) {
            expandedSessionWorkspaces.clear()
            activeWorkspaceKey?.let(expandedSessionWorkspaces::add)
            expandedSessionQuickChats = activeGroupKey == "quick-chats"
            sessionPickerActiveGroupKey = activeGroupKey
        }
    }

    private fun toggleSessionWorkspace(workspaceKey: String) {
        if (workspaceKey in expandedSessionWorkspaces) {
            expandedSessionWorkspaces.remove(workspaceKey)
        } else {
            expandedSessionWorkspaces.add(workspaceKey)
        }
        showSessionsMenu(
            anchorOverride = activeSessionsMenuAnchor ?: headerSessionAnchor ?: panelHost,
            replace = true,
            revealRowId = WorkspaceSessionPickerSections.workspaceRowId(workspaceKey),
            revealRowVerticalBias = SESSION_TOGGLE_REVEAL_BIAS
        )
    }

    private fun toggleSessionQuickChats() {
        expandedSessionQuickChats = !expandedSessionQuickChats
        showSessionsMenu(
            anchorOverride = activeSessionsMenuAnchor ?: headerSessionAnchor ?: panelHost,
            replace = true,
            revealRowId = WorkspaceSessionPickerSections.QUICK_CHATS_ROW_ID,
            revealRowVerticalBias = SESSION_TOGGLE_REVEAL_BIAS
        )
    }

    private fun activeWorkspacePathForSessionPicker(): String? {
        return workspaceHarnessId()?.let(::workspacePathForHarness)?.takeIf(HostWorkspacePaths::hasDefault)
    }

    private fun workspaceHarnessId(): String? {
        val candidates = listOfNotNull(
            lastChatState.selectedModel?.takeIf { it.isNotBlank() }?.let(ChatModelCatalog::harnessForModel),
            lastChatState.harnessId?.takeIf { it.isNotBlank() },
            ChatModelCatalog.harnessFromSessionKey(lastChatState.sessionKey)
        )
        return candidates.firstOrNull(AgentConfig::isWorkspaceHarness)
    }

    private fun isWorkspaceHarness(): Boolean {
        return workspaceHarnessId() != null
    }

    private fun workspaceSessionsForActiveHarness(): List<ChatSessionRow> {
        return WorkspaceSessionPickerSections.forHarness(lastChatState.sessions, workspaceHarnessId())
    }

    private fun startNewChatSession() {
        onNewChatSession()
        clearComposerDraft()
        setStatus("Started a new chat session")
    }

    private fun showPlusMenu(
        anchorOverride: View? = null,
        replace: Boolean = false,
        revealRowId: String? = null,
        revealRowVerticalBias: Float? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val menuAnchor: View = anchorOverride ?: headerSessionAnchor ?: panelContent ?: panelHost ?: return

        val config = AgentConfigStore.load(context)
        val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
        val modelOptions = availableModelOptions(config, localLiteRtAvailable)
        val harnessGroups = ChatPresentationHelpers.harnessModelGroups(modelOptions, config.enabledModelHarnessIds())
        val currentHarnessId = activeHarnessId(modelOptions, localLiteRtAvailable)
        val workspaceHarnessId = workspaceHarnessId()
        val sessions = if (workspaceHarnessId == null) {
            lastChatState.sessions
        } else {
            WorkspaceSessionPickerSections.forHarness(lastChatState.sessions, workspaceHarnessId)
        }
        val localMode = isLocalChatMode()
        val commands = if (localMode) emptyList() else lastChatState.commands

        val sessionRows = mutableListOf<AnchoredPicker.Row>()
        sessionRows.add(AnchoredPicker.Row(
            id = "chat:new",
            label = "New chat",
            iconRes = R.drawable.ic_new_chat,
            onSelect = { startNewChatSession() }
        ))
        if (workspaceHarnessId != null) {
            sessionRows.add(hostWorkspaceMenuRow(workspaceHarnessId))
        }
        if (sessions.isNotEmpty()) {
            val sessionCount = sessions.size.coerceAtMost(30)
            val workspaceCount = WorkspaceSessionPickerSections.workspaceCount(sessions)
            val workspaceHarnessLabel = workspaceHarnessId?.let(ChatPresentationHelpers::harnessLabel)
            sessionRows.add(AnchoredPicker.Row(
                id = "chat:previous",
                label = workspaceHarnessLabel?.let { "Previous $it sessions" } ?: "Previous chats",
                sublabel = if (workspaceHarnessId != null && workspaceCount > 0) {
                    "$sessionCount across $workspaceCount folders"
                } else {
                    "Last $sessionCount"
                },
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = lastChatState.totalUnreadReplies,
                dismissOnSelect = false,
                onSelect = { showSessionsMenu(menuAnchor) }
            ))
        }
        if (harnessGroups.size >= 2) {
            val currentHarnessLabel = harnessGroups.firstOrNull { it.id == currentHarnessId }?.label
                ?: ChatPresentationHelpers.harnessLabel(currentHarnessId)
            sessionRows.add(ChatPickerRows.harnessMenuRow(
                state = lastChatState,
                currentHarnessLabel = currentHarnessLabel,
                onSelect = { showHarnessChoices(anchorOverride = menuAnchor, replace = true) }
            ))
        }
        sessionRows.add(AnchoredPicker.Row(
            id = "picker:model",
            label = "Model",
            sublabel = ChatPresentationHelpers.formatModelLabel(
                model = lastChatState.selectedModel ?: lastChatState.models.firstOrNull()?.id,
                models = modelOptions,
                localLiteRtAvailable = localLiteRtAvailable
            ),
            iconRes = R.drawable.ic_model,
            dismissOnSelect = false,
            onSelect = { showModelChoices(anchorOverride = menuAnchor, replace = true) }
        ))
        sessionRows.add(AnchoredPicker.Row(
            id = "picker:reasoning",
            label = "Reasoning",
            sublabel = ChatPresentationHelpers.formatReasoningLabel(lastChatState.reasoningEffort),
            iconRes = R.drawable.ic_reasoning,
            dismissOnSelect = false,
            onSelect = { showReasoningChoices(anchorOverride = menuAnchor, replace = true) }
        ))
        sessionRows.add(AnchoredPicker.Row(
            id = "picker:upload-file",
            label = "Upload File",
            sublabel = "Image or file",
            iconRes = R.drawable.ic_file,
            dismissOnSelect = false,
            onSelect = { showUploadFileChoices(anchorOverride = menuAnchor, replace = true) }
        ))

        val commandSkillRows = commandSkillMenuRows(
            commands = commands,
            localMode = localMode,
            menuAnchor = menuAnchor,
            onDismiss = onDismiss
        )

        val modesAndSettingsRows = listOf(
            plusFastModeRow(),
            plusToolCallsRow(),
            AnchoredPicker.Row(
                id = "realtime:start",
                label = "Realtime Agent",
                iconRes = R.drawable.ic_voice_wave,
                onSelect = { startVoiceAndMinimizePanel() }
            ),
            AnchoredPicker.Row(
                id = "voice:start",
                label = "Voice mode",
                iconRes = R.drawable.ic_voice,
                onSelect = { startVoiceAndMinimizePanel() }
            ),
            activeSendModeRow(),
            AnchoredPicker.Row(
                id = "usage:open",
                label = "Usage",
                iconRes = R.drawable.ic_usage,
                dismissOnSelect = false,
                onSelect = { showUsageControls() }
            ),
            AnchoredPicker.Row(
                id = "settings:open",
                label = "Settings",
                iconRes = R.drawable.ic_settings_gear,
                onSelect = { dismissPanel(); openSettings() }
            )
        )

        val sections = mutableListOf<AnchoredPicker.Section>()
        sections.add(AnchoredPicker.Section("Session", sessionRows))
        if (commandSkillRows.isNotEmpty()) sections.add(AnchoredPicker.Section("Commands & Skills", commandSkillRows))
        sections.add(AnchoredPicker.Section("Modes & Settings", modesAndSettingsRows))

        showAnchoredPicker(
            menuAnchor,
            "Menu",
            sections,
            toggleSameAnchor = !replace,
            replaceShowing = replace,
            heightFraction = if (expandedCommandPickerGroups.isNotEmpty()) 0.65f else null,
            revealRowId = revealRowId,
            revealRowVerticalBias = revealRowVerticalBias,
            onDismiss = onDismiss
        )
    }

    private fun hostWorkspaceMenuRow(
        harnessId: String,
        path: String = workspacePathForHarness(harnessId)
    ): AnchoredPicker.Row {
        return AnchoredPicker.Row(
            id = PLUS_ROW_HOST_WORKSPACE,
            label = "Current Workspace",
            sublabel = HostWorkspacePaths.defaultWorkspaceLabel(path),
            iconRes = R.drawable.ic_file,
            dismissOnSelect = false,
            onSelect = { showHostWorkspaceDialog(harnessId) }
        )
    }

    private fun updateHostWorkspaceMenuRow(harnessId: String, path: String) {
        anchoredPicker?.updateRow(hostWorkspaceMenuRow(harnessId, path))
    }

    private fun refreshHostWorkspaceMenuAfterDialog(harnessId: String, path: String) {
        updateHostWorkspaceMenuRow(harnessId, path)
        val anchor = headerSessionAnchor ?: return
        if (anchoredPicker?.isShowingFor(anchor) == true) {
            showPlusMenu(
                anchorOverride = anchor,
                replace = true,
                onDismiss = { animateHeaderSessionChevron(expanded = false) }
            )
        }
    }

    private fun workspacePathForHarness(harnessId: String): String {
        return onGetWorkspacePath(harnessId)
    }

    private fun setWorkspacePathForHarness(harnessId: String, path: String) {
        onSetWorkspacePath(harnessId, path)
    }

    private fun showHostWorkspaceDialog(harnessId: String) {
        val tokens = DesignTokens.resolve(context)
        var pathToRefreshAfterDismiss: String? = null
        val harnessLabel = ChatPresentationHelpers.harnessLabel(harnessId)
        val editor = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            val workspaceText = HostWorkspacePaths.requiredHomeEditorText(workspacePathForHarness(harnessId))
            setText(workspaceText)
            setSelection(workspaceText.length)
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            keepRequiredHomePrefix(this)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(TextView(context).apply {
                text = "New $harnessLabel chats will start in this workspace folder. ~/ means your Mac home folder."
                setTextColor(tokens.secondaryText)
                textSize = 13f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(editor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Current Workspace")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val path = HostWorkspacePaths.normalizeRequiredHomeInput(editor.text?.toString())
                setWorkspacePathForHarness(harnessId, path)
                pathToRefreshAfterDismiss = path
                setStatus("$harnessLabel workspace: ${HostWorkspacePaths.defaultWorkspaceLabel(path)}")
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            pathToRefreshAfterDismiss?.let { path ->
                refreshHostWorkspaceMenuAfterDialog(harnessId, path)
            }
        }
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Drawables.glassSurface(context, tokens, DesignTokens.Radius.lg))
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            if (titleId != 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(tokens.accent)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(tokens.secondaryText)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(tokens.secondaryText)
            editor.requestFocus()
        }
        dialog.show()
    }

    private fun keepRequiredHomePrefix(editor: EditText) {
        editor.addTextChangedListener(object : TextWatcher {
            private var correcting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (correcting) return
                val current = s?.toString().orEmpty()
                val fixed = HostWorkspacePaths.requireHomePrefix(current)
                if (fixed != current) {
                    correcting = true
                    editor.setText(fixed)
                    editor.setSelection(fixed.length)
                    correcting = false
                    return
                }
                if (editor.selectionStart in 0..1) {
                    editor.setSelection(2.coerceAtMost(editor.text?.length ?: 0))
                }
            }
        })
    }

    private fun commandSkillMenuRows(
        commands: List<ChatCommandOption>,
        localMode: Boolean,
        menuAnchor: View,
        onDismiss: (() -> Unit)?
    ): List<AnchoredPicker.Row> {
        if (localMode) {
            return listOf(
                unavailableCommandSkillRow(COMMAND_GROUP_COMMANDS, "Commands"),
                unavailableCommandSkillRow(COMMAND_GROUP_SKILLS, "Skills")
            )
        }
        if (commands.isEmpty()) return emptyList()
        val slashCommands = commands.filterNot { it.isSkill }
        val skills = commands.filter { it.isSkill }
        return buildList {
            addCommandSkillGroupRow(
                groupId = COMMAND_GROUP_COMMANDS,
                label = "Commands",
                count = slashCommands.size,
                menuAnchor = menuAnchor,
                onDismiss = onDismiss
            )
            if (COMMAND_GROUP_COMMANDS in expandedCommandPickerGroups) {
                addAll(slashCommands.map(::commandMenuRow))
            }
            addCommandSkillGroupRow(
                groupId = COMMAND_GROUP_SKILLS,
                label = "Skills",
                count = skills.size,
                menuAnchor = menuAnchor,
                onDismiss = onDismiss
            )
            if (COMMAND_GROUP_SKILLS in expandedCommandPickerGroups) {
                addAll(skills.map(::skillMenuRow))
            }
        }
    }

    private fun unavailableCommandSkillRow(groupId: String, label: String): AnchoredPicker.Row {
        return AnchoredPicker.Row(
            id = commandSkillGroupRowId(groupId),
            label = label,
            sublabel = "None available in local mode",
            selectable = false,
            enabled = false,
            dismissOnSelect = false,
            onSelect = {}
        )
    }

    private fun MutableList<AnchoredPicker.Row>.addCommandSkillGroupRow(
        groupId: String,
        label: String,
        count: Int,
        menuAnchor: View,
        onDismiss: (() -> Unit)?
    ) {
        val expanded = groupId in expandedCommandPickerGroups
        add(AnchoredPicker.Row(
            id = commandSkillGroupRowId(groupId),
            label = label,
            sublabel = if (count == 0) "None available" else "$count available",
            selectable = false,
            enabled = count > 0,
            trailingIconRes = R.drawable.ic_chevron_right,
            trailingIconRotation = if (expanded) 90f else 0f,
            dismissOnSelect = false,
            onSelect = {
                if (expanded) {
                    expandedCommandPickerGroups.remove(groupId)
                } else {
                    expandedCommandPickerGroups.add(groupId)
                }
                showPlusMenu(
                    anchorOverride = menuAnchor,
                    replace = true,
                    revealRowId = commandSkillGroupRowId(groupId),
                    revealRowVerticalBias = COMMAND_GROUP_TOGGLE_REVEAL_BIAS,
                    onDismiss = onDismiss
                )
            }
        ))
    }

    private fun commandSkillGroupRowId(groupId: String): String = "commands-skills:$groupId"

    private fun isLocalChatMode(): Boolean {
        return lastChatState.modelSource == ChatModelSource.LOCAL ||
            lastChatState.harnessId == AgentConfig.HARNESS_LOCAL ||
            lastChatState.selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID
    }

    private fun commandMenuRow(command: ChatCommandOption): AnchoredPicker.Row {
        val text = SlashCommandAutocomplete.commandText(command)
        return AnchoredPicker.Row(
            id = "command:${command.name}",
            label = text,
            sublabel = command.description?.take(64),
            iconRes = R.drawable.ic_command,
            onSelect = { insertComposerText("$text ") }
        )
    }

    private fun skillMenuRow(skill: ChatCommandOption): AnchoredPicker.Row {
        val text = SlashCommandAutocomplete.skillText(skill)
        return AnchoredPicker.Row(
            id = "skill:${skill.name}",
            label = text,
            sublabel = skill.description?.take(64),
            iconRes = R.drawable.ic_command,
            onSelect = { insertComposerText("$text ") }
        )
    }

    private fun updatePlusMenuToggleRow(row: AnchoredPicker.Row) {
        if (anchoredPicker?.updateRow(row) != true) {
            showPlusMenu(
                anchorOverride = headerSessionAnchor,
                replace = true,
                onDismiss = { animateHeaderSessionChevron(expanded = false) }
            )
        }
    }

    private fun plusFastModeRow(): AnchoredPicker.Row {
        val fastModeOn = lastChatState.fastMode == true
        return AnchoredPicker.Row(
            id = PLUS_ROW_FAST_MODE,
            label = "Fast mode: ${if (fastModeOn) "On" else "Off"}",
            sublabel = if (fastModeOn) "Tap to turn off" else "Tap to turn on",
            iconRes = R.drawable.ic_bolt,
            selected = fastModeOn,
            dismissOnSelect = false,
            onSelect = {
                val nextEnabled = lastChatState.fastMode != true
                onChatControlCommand("fast", JSONObject().put("enabled", nextEnabled))
                setStatus(if (nextEnabled) "Fast mode enabled" else "Fast mode disabled")
                updatePlusMenuToggleRow(plusFastModeRow())
            }
        )
    }

    private fun plusToolCallsRow(): AnchoredPicker.Row {
        return AnchoredPicker.Row(
            id = PLUS_ROW_TOOL_CALLS,
            label = "Show Tool Calls: ${if (showToolCalls) "On" else "Off"}",
            sublabel = if (showToolCalls) "Tap to hide bash, MCP, web search, and other tool activity" else "Tap to show tool activity",
            iconRes = R.drawable.ic_tools,
            selected = showToolCalls,
            dismissOnSelect = false,
            onSelect = {
                showToolCalls = !showToolCalls
                setStatus("Tool calls ${if (showToolCalls) "shown" else "hidden"}")
                renderTimeline(lastChatState)
                updatePlusMenuToggleRow(plusToolCallsRow())
            }
        )
    }

    private fun activeSendModeRow(): AnchoredPicker.Row {
        return AnchoredPicker.Row(
            id = PLUS_ROW_ACTIVE_SEND_MODE,
            label = "Active send: ${activeSendModeLabel()}",
            sublabel = activeSendModeSublabel(),
            iconRes = R.drawable.ic_steer,
            selected = activeSendMode() == ChatActiveSendMode.Steer,
            dismissOnSelect = false,
            onSelect = {
                toggleActiveSendMode()
                updatePlusMenuToggleRow(activeSendModeRow())
            }
        )
    }

    private fun activeSendMode(): ChatActiveSendMode {
        return AgentConfigStore.load(context).activeSendMode
    }

    private fun activeSendModeLabel(): String {
        return activeSendMode().label
    }

    private fun activeSendModeSublabel(): String {
        return when (activeSendMode()) {
            ChatActiveSendMode.Queue -> "Typed messages wait for the next turn"
            ChatActiveSendMode.Steer -> "Typed messages steer after the next tool call"
        }
    }

    private fun toggleActiveSendMode() {
        val config = AgentConfigStore.load(context)
        val next = when (config.activeSendMode) {
            ChatActiveSendMode.Queue -> ChatActiveSendMode.Steer
            ChatActiveSendMode.Steer -> ChatActiveSendMode.Queue
        }
        AgentConfigStore.save(context, config.copy(activeSendMode = next))
        setStatus("Active send: ${next.label}")
    }

    private fun showSessionsMenu(
        anchorOverride: View? = null,
        replace: Boolean = false,
        revealRowId: String? = null,
        revealRowVerticalBias: Float? = null
    ) {
        val anchor = anchorOverride ?: headerSessionAnchor ?: panelHost ?: return
        activeSessionsMenuAnchor = anchor
        val sections = sessionPickerSections()
        if (sections.all { it.rows.isEmpty() }) {
            setStatus("No previous chats yet.")
            return
        }
        val title = workspaceHarnessId()
            ?.let { "Previous ${ChatPresentationHelpers.harnessLabel(it)} sessions" }
            ?: "Previous chats"
        showAnchoredPicker(
            anchor,
            title,
            sections,
            toggleSameAnchor = false,
            replaceShowing = replace,
            revealRowId = revealRowId,
            revealRowVerticalBias = revealRowVerticalBias,
            onDismiss = if (anchor === headerSessionAnchor) {
                { animateHeaderSessionChevron(expanded = false) }
            } else {
                null
            }
        )
        if (anchor === headerSessionAnchor) {
            animateHeaderSessionChevron(expanded = true)
        }
    }

    private fun showUsageControls() {
        val anchor = contextUsageView ?: panelHost ?: return
        val usage = lastChatState.usage
        val percent = usage.contextRatio?.let { "${(it * 100).roundToInt()}%" } ?: "unknown"
        val rows = listOf(
            AnchoredPicker.Row(
                id = "usage:context",
                label = "Context",
                sublabel = percent,
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                id = "usage:total_tokens",
                label = "Total tokens",
                sublabel = (usage.totalTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                id = "usage:input_tokens",
                label = "Input tokens",
                sublabel = (usage.inputTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                id = "usage:output_tokens",
                label = "Output tokens",
                sublabel = (usage.outputTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                id = "usage:refresh",
                label = "Refresh status",
                iconRes = R.drawable.ic_bolt,
                onSelect = { onChatControlCommand("status", JSONObject()); setStatus("Refreshing status") }
            )
        )
        showAnchoredPicker(anchor, "Usage", listOf(AnchoredPicker.Section(null, rows)), toggleSameAnchor = false)
    }

    private fun insertComposerText(text: String) {
        val input = composerInput ?: return
        val existing = input.text.toString()
        val separator = if (existing.isBlank() || existing.endsWith(" ")) "" else " "
        val next = existing + separator + text
        suppressComposerAutocomplete = true
        input.setText(next)
        input.setSelection(next.length)
        applySkillHighlights(input)
        suppressComposerAutocomplete = false
    }

    private fun maybeShowCommandAutocomplete(input: EditText) {
        val text = input.text?.toString().orEmpty()
        val cursor = input.selectionStart
        val skillToken = SlashCommandAutocomplete.currentSkillToken(text, cursor)
        val slashToken = SlashCommandAutocomplete.currentToken(text, cursor)
        when {
            skillToken != null -> showSkillAutocomplete(input, skillToken)
            slashToken != null -> showSlashAutocomplete(input, slashToken)
            else -> dismissComposerAutocomplete(input)
        }
    }

    private fun showSlashAutocomplete(input: EditText, token: SlashToken) {
        val commands = SlashCommandAutocomplete.matchingCommands(lastChatState.commands, token.query)
        if (commands.isEmpty()) {
            if (anchoredPicker?.isShowingFor(input) == true) {
                anchoredPicker?.dismiss()
            }
            return
        }
        val rows = commands.map { command ->
            val text = SlashCommandAutocomplete.commandText(command)
            AnchoredPicker.Row(
                id = "slash:${command.name}",
                label = text,
                sublabel = command.description?.take(72),
                iconRes = R.drawable.ic_command,
                onSelect = { autocompleteCommandText(input, token, text) }
            )
        }
        showAnchoredPicker(
            anchor = input,
            title = "Commands",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = false,
            replaceShowing = true,
            preferAbove = true
        )
    }

    private fun showSkillAutocomplete(input: EditText, token: SlashToken) {
        val skills = SlashCommandAutocomplete.matchingSkills(lastChatState.commands, token.query)
        if (skills.isEmpty()) {
            dismissComposerAutocomplete(input)
            return
        }
        val rows = skills.map { skill ->
            val text = SlashCommandAutocomplete.skillText(skill)
            AnchoredPicker.Row(
                id = "skill:${skill.name}",
                label = text,
                sublabel = skill.description?.take(72),
                iconRes = R.drawable.ic_command,
                onSelect = { autocompleteCommandText(input, token, text) }
            )
        }
        showAnchoredPicker(
            anchor = input,
            title = "Skills",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = false,
            replaceShowing = true,
            preferAbove = true
        )
    }

    private fun dismissComposerAutocomplete(input: EditText) {
        if (anchoredPicker?.isShowingFor(input) == true) {
            anchoredPicker?.dismiss()
        }
    }

    private fun autocompleteCommandText(input: EditText, token: SlashToken, commandText: String) {
        val current = input.text?.toString().orEmpty()
        val result = SlashCommandAutocomplete.applyAutocomplete(current, token, commandText)
        suppressComposerAutocomplete = true
        input.setText(result.text)
        input.setSelection(result.cursor)
        applySkillHighlights(input)
        suppressComposerAutocomplete = false
        input.requestFocus()
    }

    private fun applySkillHighlights(input: EditText) {
        val editable = input.text ?: return
        val existingSpans = editable.getSpans(0, editable.length, SkillTokenSpan::class.java)
        existingSpans.forEach { span -> editable.removeSpan(span) }

        if (editable.isEmpty()) return
        val skillNames = lastChatState.commands
            .filter { it.isSkill }
            .map { it.name.lowercase() }
            .toSet()
        if (skillNames.isEmpty()) return

        var index = 0
        while (index < editable.length) {
            val tokenStart = editable.indexOf('$', index)
            if (tokenStart < 0) return
            val isTokenBoundary = tokenStart == 0 || editable[tokenStart - 1].isWhitespace()
            if (!isTokenBoundary) {
                index = tokenStart + 1
                continue
            }
            var tokenEnd = tokenStart + 1
            while (tokenEnd < editable.length && !editable[tokenEnd].isWhitespace()) {
                tokenEnd += 1
            }
            val skillName = editable.subSequence(tokenStart + 1, tokenEnd).toString().lowercase()
            if (skillName in skillNames) {
                editable.setSpan(
                    SkillTokenSpan(tokens().accent),
                    tokenStart,
                    tokenEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            index = tokenEnd + 1
        }
    }

    private fun renderChatState(state: ChatState) {
        val tokens = tokens()
        renderHeaderBrand(tokens, state)
        attachmentTray.render(tokens)
        renderComposerActionButtons(tokens, state, lastTranscriptionState)
        composerInput?.hint = brandPresentationFor(state).copy.composerPlaceholder
        composerInput?.let { applySkillHighlights(it) }
        modelButton?.let { btn ->
            val fastModeOn = state.fastMode == true
            val config = AgentConfigStore.load(context)
            val localLiteRtAvailable = isExperimentalLocalModelAvailable(config)
            val modelOptions = ChatPresentationHelpers.modelPickerOptions(state, localLiteRtAvailable, config.enabledModelHarnessIds())
            val modelLabel = ChatPresentationHelpers.formatModelLabel(
                model = state.selectedModel ?: state.models.firstOrNull()?.id,
                models = modelOptions,
                localLiteRtAvailable = localLiteRtAvailable
            )
            btn.text = modelLabel
            btn.updateAccessibilityState(
                description = "Model selector",
                stateDescription = if (fastModeOn) "$modelLabel, fast mode on" else modelLabel
            )
            btn.setTextColor(if (fastModeOn) tokens.accent else tokens.primaryText)
            val chevron = androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.ic_chevron_down)?.mutate()?.apply {
                    setTint(tokens.secondaryText)
                    setBounds(0, 0, dp(12), dp(12))
                }
            val left = androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.ic_model)?.mutate()?.apply {
                    setTint(if (fastModeOn) tokens.accent else tokens.secondaryText)
                    setBounds(0, 0, dp(14), dp(14))
                }
            btn.setCompoundDrawables(left, null, chevron, null)
        }
        reasoningButton?.let { btn ->
            val reasoningLabel = ChatPresentationHelpers.formatReasoningLabel(state.reasoningEffort)
            btn.text = reasoningLabel
            btn.updateAccessibilityState(
                description = "Reasoning selector",
                stateDescription = reasoningLabel
            )
        }
        contextUsageView?.bind(tokens, state.usage.contextRatio)
        statusText?.let { sv ->
            sv.setText(chatStatusText(state.status, state))
            sv.setActive(state.isRunning)
        }
        modelButton?.takeIf { anchoredPicker?.isShowingFor(it) == true }?.let { anchor ->
            showModelChoices(anchorOverride = anchor, replace = true)
        }
        bubbleOverlay.renderChatState(state)
        renderTimeline(state)
    }

    private fun notifyCurrentChatSessionViewed() {
        if (!isPanelActivelyViewed()) return
        lastChatState.sessionKey?.takeIf { it.isNotBlank() }?.let(onChatSessionViewed)
    }

    private fun notifyCurrentChatSessionOpened() {
        if (!isPanelActivelyViewed()) return
        lastChatState.sessionKey?.takeIf { it.isNotBlank() }?.let(onChatSessionOpened)
    }

    /**
     * Reacts to the chat panel's Android window focus changing.
     *
     * The panel runs as a TYPE_APPLICATION_OVERLAY, so it can stay attached
     * even after the user pressed home or switched apps. We use window focus
     * as the proxy for "the user is actively engaged with the chat":
     *
     * - On focus gained, mark the active session as viewed (clears any unread
     *   replies that arrived while we were backgrounded).
     * - On focus lost while in fullscreen mode, restore the floating bubble so
     *   the user has a surface showing the thinking animation / unread badge
     *   on whatever screen is now visible.
     */
    private fun handlePanelWindowFocusChanged(hasWindowFocus: Boolean) {
        if (panelHasWindowFocus == hasWindowFocus) return
        panelHasWindowFocus = hasWindowFocus
        if (hasWindowFocus) {
            if (activePanelPresentation == PanelPresentation.Shell) {
                suppressBubbleForShellChat()
            }
            notifyCurrentChatSessionViewed()
        } else {
            if (activePanelPresentation == PanelPresentation.Fullscreen && !bubbleOverlay.isVisible) {
                // User backgrounded the fullscreen chat (home, app switcher,
                // another app on top). Bring the bubble back so it can reflect
                // chat state. Preserve the original dismiss-time restore intent
                // so the regular dismiss path stays a no-op for the bubble.
                restoreBubbleAfterFullscreen = true
                showInternal(allowDuringFullscreenPanel = true)
            } else if (activePanelPresentation == PanelPresentation.Shell && restoreBubbleAfterShellChat && !bubbleOverlay.isVisible) {
                showInternal(allowDuringFullscreenPanel = true)
            }
        }
    }

    private fun renderTimeline(state: ChatState) {
        chatTimelineBinder.render(state, showToolCalls, brandPresentationFor(state))
    }

    private data class RevealCenter(val cx: Int, val cy: Int, val bubbleRadius: Float)

    private fun appearancePrefs(): AppearancePrefs = AppearancePrefsStore.load(context)

    private fun bubbleScreenCenter(): Pair<Int, Int>? {
        return bubbleOverlay.screenCenter()
    }

    private fun screenCenterFallback(): Pair<Int, Int> {
        val display = context.resources.displayMetrics
        return (display.widthPixels / 2) to (display.heightPixels / 2)
    }

    private fun revealCenterForPanel(panelParams: WindowManager.LayoutParams?): RevealCenter {
        val panelY = panelParams?.y ?: 0
        val bubble = bubbleScreenCenter()
        if (bubble != null) {
            return RevealCenter(bubble.first, bubble.second - panelY, dp(44).toFloat())
        }
        val fallback = screenCenterFallback()
        return RevealCenter(fallback.first, fallback.second - panelY, 0f)
    }

    private fun revealCenterForScrim(): RevealCenter {
        val bubble = bubbleScreenCenter()
        if (bubble != null) {
            return RevealCenter(bubble.first, bubble.second, dp(44).toFloat())
        }
        val fallback = screenCenterFallback()
        return RevealCenter(fallback.first, fallback.second, 0f)
    }

    private fun maxRevealRadius(view: View, cx: Int, cy: Int): Float {
        val width = view.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = view.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val dx = max(cx, width - cx).toFloat()
        val dy = max(cy, height - cy).toFloat()
        return hypot(dx, dy)
    }

    private fun snapHistoryToBottom() {
        chatTimelineBinder.snapToBottom()
    }

    private fun cancelPanelOpenAnimators() {
        panelOpenAnimator?.cancel()
        panelOpenAnimator = null
        panelScrimOpenAnimator?.cancel()
        panelScrimOpenAnimator = null
    }

    private fun cancelPanelCloseAnimators() {
        panelCloseAnimator?.cancel()
        panelCloseAnimator = null
        panelScrimCloseAnimator?.cancel()
        panelScrimCloseAnimator = null
    }

    private fun runPanelOpenAnimation(
        host: View,
        scrim: View,
        prefs: AppearancePrefs,
        defaultHeight: Int
    ) {
        cancelPanelOpenAnimators()
        when (prefs.panelAnimation) {
            PanelAnimationStyle.Slide -> {
                host.post {
                    val startOffset = (host.height.takeIf { it > 0 } ?: defaultHeight).toFloat()
                    host.translationY = startOffset
                    host.animate()
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                scrim.alpha = 0f
                scrim.animate().alpha(1f).setDuration(180L).start()
            }
            PanelAnimationStyle.Circular -> {
                host.translationY = 0f
                host.visibility = View.INVISIBLE
                scrim.alpha = 0f
                scrim.visibility = View.INVISIBLE
                host.post {
                    if (!host.isAttachedToWindow || host.width == 0 || host.height == 0) {
                        snapHistoryToBottom()
                        host.visibility = View.VISIBLE
                        return@post
                    }
                    val center = revealCenterForPanel(panelParams)
                    val finalRadius = maxRevealRadius(host, center.cx, center.cy)
                    snapHistoryToBottom()
                    host.visibility = View.VISIBLE
                    val anim = ViewAnimationUtils.createCircularReveal(
                        host,
                        center.cx,
                        center.cy,
                        center.bubbleRadius,
                        finalRadius
                    ).apply {
                        duration = 280L
                        interpolator = DecelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (panelOpenAnimator === animation) {
                                    panelOpenAnimator = null
                                }
                            }
                        })
                    }
                    panelOpenAnimator = anim
                    anim.start()
                }
                scrim.post {
                    if (!scrim.isAttachedToWindow || scrim.width == 0 || scrim.height == 0) {
                        scrim.alpha = 1f
                        scrim.visibility = View.VISIBLE
                        return@post
                    }
                    val center = revealCenterForScrim()
                    val finalRadius = maxRevealRadius(scrim, center.cx, center.cy)
                    scrim.alpha = 1f
                    scrim.visibility = View.VISIBLE
                    val anim = ViewAnimationUtils.createCircularReveal(
                        scrim,
                        center.cx,
                        center.cy,
                        center.bubbleRadius,
                        finalRadius
                    ).apply {
                        duration = 280L
                        interpolator = DecelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (panelScrimOpenAnimator === animation) {
                                    panelScrimOpenAnimator = null
                                }
                            }
                        })
                    }
                    panelScrimOpenAnimator = anim
                    anim.start()
                }
            }
        }
    }

    private fun runPanelCloseAnimation(
        host: View,
        scrim: View?,
        prefs: AppearancePrefs,
        onEnd: () -> Unit
    ) {
        cancelPanelCloseAnimators()
        when (prefs.panelAnimation) {
            PanelAnimationStyle.Slide -> {
                val translate = host.height.takeIf { it > 0 }?.toFloat()
                    ?: context.resources.displayMetrics.heightPixels.toFloat()
                host.animate()
                    .translationY(translate)
                    .setDuration(220L)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction(onEnd)
                    .start()
                scrim?.animate()
                    ?.alpha(0f)
                    ?.setDuration(220L)
                    ?.start()
            }
            PanelAnimationStyle.Circular -> {
                if (host.width == 0 || host.height == 0 || !host.isAttachedToWindow) {
                    onEnd()
                    return
                }
                val center = revealCenterForPanel(panelParams)
                val startRadius = maxRevealRadius(host, center.cx, center.cy)
                val anim = ViewAnimationUtils.createCircularReveal(
                    host,
                    center.cx,
                    center.cy,
                    startRadius,
                    center.bubbleRadius
                ).apply {
                    duration = 240L
                    interpolator = AccelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        private var ended = false
                        override fun onAnimationEnd(animation: Animator) {
                            if (panelCloseAnimator === animation) {
                                panelCloseAnimator = null
                            }
                            if (!ended) {
                                ended = true
                                onEnd()
                            }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            if (panelCloseAnimator === animation) {
                                panelCloseAnimator = null
                            }
                            if (!ended) {
                                ended = true
                                onEnd()
                            }
                        }
                    })
                }
                panelCloseAnimator = anim
                anim.start()

                if (scrim != null && scrim.isAttachedToWindow && scrim.width > 0 && scrim.height > 0) {
                    val scrimCenter = revealCenterForScrim()
                    val scrimStart = maxRevealRadius(scrim, scrimCenter.cx, scrimCenter.cy)
                    val scrimAnim = ViewAnimationUtils.createCircularReveal(
                        scrim,
                        scrimCenter.cx,
                        scrimCenter.cy,
                        scrimStart,
                        scrimCenter.bubbleRadius
                    ).apply {
                        duration = 240L
                        interpolator = AccelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (panelScrimCloseAnimator === animation) {
                                    panelScrimCloseAnimator = null
                                }
                                scrim.alpha = 0f
                            }
                        })
                    }
                    panelScrimCloseAnimator = scrimAnim
                    scrimAnim.start()
                }
            }
        }
    }

    private fun dismissPanel(cancelTranscription: Boolean = true, force: Boolean = false) {
        if (cancelTranscription && lastTranscriptionState.isRecording) {
            onCancelTranscription()
        }
        dismissHostConnectionPopup()
        anchoredPicker?.dismiss()
        anchoredPicker = null

        val panel = panelView
        val scrim = panelScrimView
        if (panel == null) {
            finalizePanelDismiss()
            return
        }
        if (force) {
            cancelPanelOpenAnimators()
            cancelPanelCloseAnimators()
            panel.animate().cancel()
            scrim?.animate()?.cancel()
            finalizePanelDismiss()
            return
        }
        if (panelDismissAnimating) {
            return
        }
        panelDismissAnimating = true
        cancelPanelOpenAnimators()

        runPanelCloseAnimation(panel, scrim, appearancePrefs()) {
            panelDismissAnimating = false
            finalizePanelDismiss()
        }
    }

    private fun finalizePanelDismiss() {
        val dismissedPresentation = activePanelPresentation
        panelDismissAnimating = false
        cancelPanelOpenAnimators()
        cancelPanelCloseAnimators()
        if (dismissedPresentation == PanelPresentation.Shell) {
            (panelView?.parent as? ViewGroup)?.removeView(panelView)
        } else {
            detachOverlayView(windowManager, panelView)
            detachOverlayView(windowManager, panelScrimView)
        }
        panelView = null
        panelParams = null
        panelScrimView = null
        panelScrimParams = null
        panelHasWindowFocus = false
        activePanelPresentation = PanelPresentation.Popup
        panelHost = null
        panelContent = null
        composerInput = null
        transcriptionMicButton = null
        voicePanel.clear()
        attachmentTray.clearSurface()
        chatTimelineBinder.clear()
        composerContainer = null
        keyboardSpacerView = null
        sendStopButton = null
        modelButton = null
        reasoningButton = null
        contextUsageView = null
        headerSessionAnchor = null
        headerSessionChevron = null
        headerBrandLogo = null
        headerBrandTitle = null
        connectionIndicatorButton = null
        when (dismissedPresentation) {
            PanelPresentation.Fullscreen -> restoreBubbleAfterFullscreenDismiss()
            PanelPresentation.Shell -> restoreBubbleAfterShellChatDismiss()
            PanelPresentation.Popup -> Unit
        }
    }

    private fun showVoiceSurface() {
        if (panelView == null) {
            togglePanel(PanelPresentation.Popup)
        }
        voicePanel.show()
    }

    private fun renderVoiceState(state: VoiceRuntimeState) {
        bubbleOverlay.applyVoiceIndicator(state)
        voicePanel.render(state)
    }

    private fun renderTranscriptionState(state: VoiceTranscriptionState) {
        val tokens = tokens()
        renderComposerActionButtons(tokens, lastChatState, state)

        when {
            state.error != null -> setStatus("Transcription error: ${state.error}")
            state.isTranscribing -> setStatus("Transcribing audio...")
            state.isRecording -> {
                val levelPercent = (state.audioLevel * 100f).roundToInt().coerceIn(0, 100)
                setStatus("Recording for transcription. Level $levelPercent%. Tap X to cancel, or stop to transcribe.")
            }
        }
    }

    private fun renderComposerActionButtons(
        tokens: ThemeTokens,
        chatState: ChatState,
        transcriptionState: VoiceTranscriptionState
    ) {
        transcriptionMicButton?.apply {
            isEnabled = !transcriptionState.isTranscribing
            setImageResource(if (transcriptionState.isRecording) R.drawable.ic_close else R.drawable.ic_mic)
            updateAccessibilityState(description = when {
                transcriptionState.isRecording -> "Cancel voice transcription"
                transcriptionState.isTranscribing -> "Transcribing audio"
                else -> "Start voice transcription"
            })
            background = if (transcriptionState.isRecording) {
                Drawables.dangerSurface(context, tokens, DesignTokens.Radius.pill)
            } else {
                Drawables.pillSurface(context, tokens)
            }
            backgroundTintList = null
            setColorFilter(if (transcriptionState.isRecording) tokens.accentInk else tokens.primaryText)
        }

        sendStopButton?.apply {
            val hasComposerText = composerInput?.text?.toString()?.trim()?.isNotEmpty() == true
            val hasComposerContent = hasComposerText || attachmentTray.hasContent()
            val shouldShowStop = transcriptionState.isRecording || (chatState.isRunning && !hasComposerContent)
            setImageResource(if (shouldShowStop) R.drawable.ic_stop else R.drawable.ic_send)
            updateAccessibilityState(description = when {
                transcriptionState.isRecording -> "Stop recording and transcribe"
                chatState.isRunning && hasComposerContent -> "Send ${activeSendModeLabel().lowercase()} message"
                chatState.isRunning -> brandPresentationFor(chatState).copy.stopTurnDescription
                else -> "Send message"
            })
            background = Drawables.accentSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setColorFilter(tokens.accentInk)
        }
    }

    private inner class ContextUsageView(context: Context) : View(context) {
        private var tokens: ThemeTokens = tokens()
        private var ratio: Float? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val arcBounds = RectF()

        init {
            // Subtle ring background so the control reads as a button while idle.
            background = Drawables.pillSurface(context, tokens)
            exposeToAccessibility(
                viewId = R.id.openclaw_context_usage_button,
                description = "Context usage",
                focusable = true
            )
        }

        fun bind(tokens: ThemeTokens, ratio: Float?) {
            this.tokens = tokens
            this.ratio = ratio
            background = Drawables.pillSurface(context, tokens)
            updateAccessibilityState(
                description = "Context usage",
                stateDescription = ratio?.let { "Context window ${(it * 100).roundToInt()} percent used" } ?: "Unknown"
            )
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = dp(2).toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - stroke - dp(3)
            trackPaint.strokeWidth = stroke
            trackPaint.color = DesignTokens.withAlpha(tokens.secondaryText, 0x55)
            progressPaint.strokeWidth = stroke
            progressPaint.color = tokens.accent
            textPaint.color = tokens.secondaryText
            textPaint.textSize = DesignTokens.sp(context, 9.5f)

            arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawCircle(centerX, centerY, radius, trackPaint)
            ratio?.let { canvas.drawArc(arcBounds, -90f, it.coerceIn(0f, 1f) * 360f, false, progressPaint) }

            val label = ratio?.let { "${(it * 100).roundToInt()}" } ?: "--"
            val baseline = centerY - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(label, centerX, baseline, textPaint)
        }
    }

    private fun suppressBubbleForFullscreen() {
        restoreBubbleAfterFullscreen = bubbleOverlay.suppressForFullscreen()
    }

    private fun restoreBubbleAfterFullscreenDismiss() {
        val shouldRestore = restoreBubbleAfterFullscreen
        restoreBubbleAfterFullscreen = false
        if (shouldRestore && Settings.canDrawOverlays(context) && automationSuppressionDepth == 0 && !bubbleOverlay.isVisible) {
            show()
        }
    }

    private fun suppressBubbleForShellChat() {
        restoreBubbleAfterShellChat = bubbleOverlay.suppressForFullscreen() || restoreBubbleAfterShellChat
    }

    private fun restoreBubbleAfterShellChatDismiss() {
        val shouldRestore = restoreBubbleAfterShellChat
        restoreBubbleAfterShellChat = false
        if (shouldRestore && Settings.canDrawOverlays(context) && automationSuppressionDepth == 0 && !bubbleOverlay.isVisible) {
            show()
        }
    }

    private fun isShellChatActivelyViewed(): Boolean {
        return activePanelPresentation == PanelPresentation.Shell && panelHasWindowFocus
    }

    private fun isFullscreenPanelAttached(): Boolean {
        return activePanelPresentation == PanelPresentation.Fullscreen && isOverlayAttached(panelView)
    }

    private fun restoreSuppressedPanel(
        restoreScrim: Boolean,
        restorePanel: Boolean,
        restorePanelFocus: Boolean,
        restoreComposerFocus: Boolean
    ) {
        val scrim = panelScrimView
        val scrimParams = panelScrimParams
        if (restoreScrim && scrim != null && scrimParams != null && !isOverlayAttached(scrim)) {
            windowManager.addView(scrim, scrimParams)
        }

        val panel = panelView
        val params = panelParams
        if (restorePanel && panel != null && params != null && !isOverlayAttached(panel)) {
            windowManager.addView(panel, params)
            when {
                restoreComposerFocus -> composerInput?.post {
                    composerInput?.requestFocus()
                    positionPanelAboveKeyboard(panel, params)
                }
                restorePanelFocus -> panel.post { panel.requestFocus() }
            }
        }
    }

    companion object {
        private const val CHAT_MODAL_HEIGHT_FRACTION = 0.82f
        private const val KEYBOARD_HEIGHT_ESTIMATE_FRACTION = 0.485f
        private const val KEYBOARD_COMPOSER_GAP_DP = 4
        private const val FULLSCREEN_KEYBOARD_BOTTOM_CLEARANCE_DP = 28
        private const val PICKER_KEYBOARD_DISMISS_INITIAL_DELAY_MS = 80L
        private const val PICKER_KEYBOARD_DISMISS_POLL_MS = 40L
        private const val PICKER_KEYBOARD_DISMISS_TIMEOUT_MS = 420L
        private const val PLUS_ROW_HOST_WORKSPACE = "chat:host-workspace"
        private const val PLUS_ROW_FAST_MODE = "plus_fast_mode"
        private const val PLUS_ROW_TOOL_CALLS = "plus_tool_calls"
        private const val PLUS_ROW_ACTIVE_SEND_MODE = "plus_active_send_mode"
        private const val COMMAND_GROUP_COMMANDS = "commands"
        private const val COMMAND_GROUP_SKILLS = "skills"
        const val MIN_BUBBLE_SIZE_DP = AppearancePrefs.MIN_BUBBLE_SIZE_DP
        const val DEFAULT_BUBBLE_SIZE_DP = AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP
        const val MAX_BUBBLE_SIZE_DP = AppearancePrefs.MAX_BUBBLE_SIZE_DP
    }

    private fun openSettings() {
        context.startActivity(AppShellActivity.openSettingsIntent(context))
    }

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        windowView: View = view,
        keepAboveKeyboard: Boolean = false,
        onDragStart: () -> Unit = {},
        onDrag: (WindowManager.LayoutParams, View) -> Unit = { _, _ -> },
        onDragEnd: (WindowManager.LayoutParams, View) -> Unit = { _, _ -> },
        onDragCancel: () -> Unit = {},
        onClick: () -> Unit
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTime = event.eventTime
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        onDragStart()
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        keepInsideScreen(windowView, params)
                        if (keepAboveKeyboard) {
                            keepAboveKeyboard(windowView, params)
                        }
                        windowManager.updateViewLayout(windowView, params)
                        onDrag(params, windowView)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val isClick = !moved && event.eventTime - downTime < 250
                    if (isClick) {
                        onClick()
                    } else {
                        onDragEnd(params, windowView)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        onDragCancel()
                    }
                    moved = false
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun keepInsideScreen(view: View, params: WindowManager.LayoutParams) {
        val display = context.resources.displayMetrics
        val horizontalInset = if (view.width >= display.widthPixels - dp(4)) 0 else dp(8)
        val maxX = (display.widthPixels - view.width - horizontalInset).coerceAtLeast(horizontalInset)
        val maxY = (display.heightPixels - view.height - dp(8)).coerceAtLeast(dp(8))
        params.x = params.x.coerceIn(horizontalInset, maxX)
        params.y = params.y.coerceIn(dp(8), maxY)
    }

    private fun keepAboveKeyboard(view: View, params: WindowManager.LayoutParams?) {
        positionPanelAboveKeyboard(view, params)
    }

    private fun positionPanelAboveKeyboard(panel: View, params: WindowManager.LayoutParams?) {
        val displayHeight = context.resources.displayMetrics.heightPixels
        val defaultBounds = panelDefaultBounds(displayHeight)
        val defaultBottom = defaultBounds.y + defaultBounds.height
        val imeHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            panel.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            0
        }
        val visibleFrameKeyboardTop = keyboardTopFromVisibleFrame(defaultBottom)
        if (visibleFrameKeyboardTop != null) {
            stableKeyboardFrameObserved = true
        }
        val rawKeyboardTop = if (visibleFrameKeyboardTop != null) {
            keyboardFallbackSuppressed = false
            visibleFrameKeyboardTop
        } else if (imeHeight >= dp(120)) {
            keyboardFallbackSuppressed = false
            defaultBottom - imeHeight
        } else if (stableKeyboardFrameObserved) {
            suppressKeyboardFallback()
            displayHeight
        } else if (composerInput?.hasFocus() == true && isKeyboardFallbackActive()) {
            defaultBottom - estimatedKeyboardHeight(displayHeight)
        } else {
            displayHeight
        }
        val panelLocation = IntArray(2)
        panel.getLocationOnScreen(panelLocation)
        val panelScreenTop = panelLocation[1].takeIf { panel.height > 0 } ?: defaultBounds.y
        val minUsableKeyboardTop = panelScreenTop + dp(240)
        val keyboardTop = if (displayHeight - rawKeyboardTop < dp(120)) {
            lastUsableKeyboardTop = null
            rawKeyboardTop
        } else if (rawKeyboardTop >= minUsableKeyboardTop) {
            lastUsableKeyboardTop = rawKeyboardTop
            rawKeyboardTop
        } else {
            lastUsableKeyboardTop ?: rawKeyboardTop
        }
        if (defaultBottom - keyboardTop < dp(120)) {
            if (params != null) {
                restorePanelDefaultSize(panel, params)
            } else {
                setKeyboardSpacerHeight(0)
            }
            return
        }

        panel.translationY = 0f
        if (activePanelPresentation == PanelPresentation.Fullscreen || activePanelPresentation == PanelPresentation.Shell) {
            val nextSpacerHeight = PanelKeyboardLayout.fullscreenKeyboardSpacerHeight(
                defaultBounds = defaultBounds,
                keyboardTop = keyboardTop,
                bottomClearance = keyboardBottomClearance()
            )
            setKeyboardSpacerHeight(nextSpacerHeight)
            if (params != null && activePanelPresentation == PanelPresentation.Fullscreen) {
                if (params.height != defaultBounds.height || params.y != defaultBounds.y) {
                    params.height = defaultBounds.height
                    params.y = defaultBounds.y
                    windowManager.updateViewLayout(panel, params)
                }
            }
            anchoredPicker?.reposition()
            return
        }

        val popupSpacerHeight = PanelKeyboardLayout.fullscreenKeyboardSpacerHeight(
            defaultBounds = PanelBounds(
                height = panel.height.takeIf { it > 0 } ?: defaultBounds.height,
                y = panelScreenTop
            ),
            keyboardTop = keyboardTop,
            bottomClearance = keyboardComposerGap()
        )
        setKeyboardSpacerHeight(popupSpacerHeight)
        if (params != null && (params.height != defaultBounds.height || params.y != defaultBounds.y)) {
            params.height = defaultBounds.height
            params.y = defaultBounds.y
            windowManager.updateViewLayout(panel, params)
            anchoredPicker?.reposition()
        }
        anchoredPicker?.reposition()
    }

    private fun keyboardTopFromVisibleFrame(defaultPanelBottom: Int): Int? {
        val anchor = when {
            panelScrimView != null && isOverlayAttached(panelScrimView) -> panelScrimView
            activePanelPresentation == PanelPresentation.Shell -> panelView
            else -> return null
        } ?: return null
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        val keyboardOverlap = defaultPanelBottom - visible.bottom
        return visible.bottom.takeIf { keyboardOverlap >= dp(120) }
    }

    private fun armKeyboardFallback() {
        keyboardFallbackSuppressed = false
    }

    private fun suppressKeyboardFallback() {
        keyboardFallbackSuppressed = true
    }

    private fun isKeyboardFallbackActive(): Boolean {
        return !keyboardFallbackSuppressed
    }

    private fun restorePanelDefaultSize(panel: View, params: WindowManager.LayoutParams) {
        val displayHeight = context.resources.displayMetrics.heightPixels
        val defaultBounds = panelDefaultBounds(displayHeight)
        panel.animate().cancel()
        panel.translationY = 0f
        setKeyboardSpacerHeight(0)
        if (params.height != defaultBounds.height || params.y != defaultBounds.y) {
            params.height = defaultBounds.height
            params.y = defaultBounds.y
            windowManager.updateViewLayout(panel, params)
            anchoredPicker?.reposition()
        }
    }

    private fun setKeyboardSpacerHeight(height: Int) {
        val spacer = keyboardSpacerView ?: return
        val nextHeight = height.coerceAtLeast(0)
        if (nextHeight == 0) {
            if (spacer.visibility != View.GONE) {
                spacer.visibility = View.GONE
            }
        } else if (spacer.visibility != View.VISIBLE) {
            spacer.visibility = View.VISIBLE
        }
        val params = spacer.layoutParams
        if (params.height != nextHeight) {
            params.height = nextHeight
            spacer.layoutParams = params
        }
    }

    private fun panelDefaultBounds(
        displayHeight: Int,
        presentation: PanelPresentation = activePanelPresentation,
        shellHeight: Int? = null
    ): PanelBounds {
        if (presentation == PanelPresentation.Shell) {
            val height = shellHeight ?: panelView?.height?.takeIf { it > 0 } ?: displayHeight
            return PanelBounds(height = height, y = 0)
        }
        return PanelKeyboardLayout.defaultBounds(
            displayHeight = displayHeight,
            presentation = presentation,
            popupHeightFraction = CHAT_MODAL_HEIGHT_FRACTION,
            fullscreenHeight = fullscreenPanelHeight(displayHeight)
        )
    }

    private fun fullscreenPanelHeight(displayHeight: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val systemBars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val usableHeight = (metrics.bounds.height() - systemBars.top - systemBars.bottom)
                .coerceAtLeast(dp(360))
            return displayHeight.coerceAtMost(usableHeight)
        }
        return displayHeight
    }

    private fun keyboardComposerGap(): Int {
        return PanelKeyboardLayout.composerGap(
            baseGap = dp(KEYBOARD_COMPOSER_GAP_DP),
            fullscreenExtraGap = dp(DesignTokens.Spacing.sm),
            presentation = activePanelPresentation
        )
    }

    private fun keyboardBottomClearance(): Int {
        return PanelKeyboardLayout.bottomClearance(
            fullscreenClearance = dp(FULLSCREEN_KEYBOARD_BOTTOM_CLEARANCE_DP),
            presentation = activePanelPresentation
        )
    }

    private fun estimatedKeyboardHeight(displayHeight: Int): Int {
        return PanelKeyboardLayout.estimatedKeyboardHeight(
            displayHeight = displayHeight,
            fraction = KEYBOARD_HEIGHT_ESTIMATE_FRACTION,
            minHeight = dp(260),
            maxFraction = 0.42f
        )
    }

    private fun tokens(): ThemeTokens = DesignTokens.resolve(context)

    private fun isNightMode(): Boolean = DesignTokens.isNightMode(context)

    private fun withAlpha(color: Int, alpha: Int): Int = DesignTokens.withAlpha(color, alpha)

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                context.getColor(typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            fallback
        }
    }
}
