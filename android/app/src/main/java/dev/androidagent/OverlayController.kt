package dev.androidagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
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
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.FrameLayout
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.chat.ChatState
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.overlay.BubbleOverlay
import dev.androidagent.overlay.ChatTimelineBinder
import dev.androidagent.overlay.ConfirmationOverlay
import dev.androidagent.overlay.HostConnectionCopy
import dev.androidagent.overlay.HostConnectionIndicatorButton
import dev.androidagent.overlay.HostConnectionPhase
import dev.androidagent.overlay.HostConnectionState
import dev.androidagent.overlay.PanelBounds
import dev.androidagent.overlay.PanelChrome
import dev.androidagent.overlay.PanelKeyboardLayout
import dev.androidagent.overlay.PanelPresentation
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

class OverlayController(
    private val context: Context,
    private val onSubmit: (String) -> Boolean,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onStartVoice: () -> Unit,
    private val onToggleVoiceMute: () -> Unit,
    private val onStopVoice: () -> Unit,
    private val onStartTranscription: () -> Unit,
    private val onStopTranscription: () -> Unit,
    private val onCancelTranscription: () -> Unit,
    private val onSelectChatSession: (String) -> Unit = {},
    private val onNewChatSession: () -> Unit = {},
    private val onSetChatModel: (String) -> Unit = {},
    private val onSetChatReasoning: (String) -> Unit = {},
    private val onChatControlCommand: (String, JSONObject) -> Unit = { _, _ -> },
    private val onToggleChatTool: (String) -> Unit = {},
    private val onChatSessionViewed: (String) -> Unit = {}
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
        onToggleChatTool = onToggleChatTool
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
    private var showToolCalls = true
    private var suppressSlashAutocomplete = false
    private var composerContainer: LinearLayout? = null
    private var keyboardSpacerView: View? = null
    private var sendStopButton: ImageButton? = null
    private var modelButton: TextView? = null
    private var reasoningButton: TextView? = null
    private var contextUsageView: ContextUsageView? = null
    private var composerInput: EditText? = null
    private var transcriptionMicButton: ImageButton? = null
    private var lastTranscriptionState = VoiceTranscriptionState()
    private var automationSuppressionDepth = 0
    private var restoreBubbleAfterAutomation = false
    private var restoreBubbleAfterFullscreen = false
    private var restorePanelAfterAutomation = false
    private var restorePanelScrimAfterAutomation = false
    private var restorePanelFocusAfterAutomation = false
    private var restoreComposerFocusAfterAutomation = false
    private var keyboardFallbackSuppressed = false
    private var stableKeyboardFrameObserved = false
    private var activePanelPresentation = PanelPresentation.Popup

    private var panelHost: FrameLayout? = null
    private var panelContent: LinearLayout? = null
    private var anchoredPicker: AnchoredPicker? = null
    private var headerSessionAnchor: View? = null
    private var headerSessionChevron: ImageView? = null
    private var connectionIndicatorButton: HostConnectionIndicatorButton? = null
    private var connectionPopupView: View? = null
    private var connectionPopupScrimView: View? = null
    private var plusButton: ImageButton? = null
    private var lastHostConnectionState = HostConnectionState(
        phase = HostConnectionPhase.CONNECTING,
        message = "Checking host connection..."
    )

    private data class SlashToken(val start: Int, val end: Int, val query: String)

    fun show() {
        showInternal(allowDuringFullscreenPanel = false)
    }

    private fun showInternal(allowDuringFullscreenPanel: Boolean) {
        if (
            !Settings.canDrawOverlays(context) ||
            bubbleOverlay.isVisible ||
            automationSuppressionDepth > 0 ||
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
        statusText?.setText(text)
    }

    fun minimizePanelFromSystemHome() {
        mainHandler.post {
            if (panelView != null) {
                dismissPanel()
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
        mainHandler.post {
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

    fun askConfirmation(message: String, preview: String?): CompletableDeferred<Boolean> {
        return confirmationOverlay.ask(message, preview)
    }

    fun openPanel(presentation: PanelPresentation = PanelPresentation.Popup) {
        mainHandler.post {
            if (panelView != null) {
                if (activePanelPresentation == presentation) {
                    notifyCurrentChatSessionViewed()
                    return@post
                }
                dismissPanel(force = true)
            }
            togglePanel(presentation)
        }
    }

    private fun togglePanel(presentation: PanelPresentation = PanelPresentation.Popup) {
        if (panelView != null) {
            dismissPanel()
            return
        }

        activePanelPresentation = presentation
        if (presentation == PanelPresentation.Fullscreen) {
            suppressBubbleForFullscreen()
        }
        val tokens = tokens()
        val input = buildComposerInput(tokens)
        val status = StatusUpdateView(context, tokens).apply {
            setText(lastChatState.status ?: "OpenClaw chat ready.")
            setActive(lastChatState.isRunning)
        }
        statusText = status
        val voice = voicePanel.build(tokens)
        val composer = buildComposer(tokens, input)
        val header = buildModalHeader(tokens, presentation)

        val display = context.resources.displayMetrics
        val defaultBounds = panelDefaultBounds(display.heightPixels, presentation)
        val handle = panelChrome.build(
            tokens = tokens,
            presentation = presentation,
            header = header,
            voice = voice,
            status = status,
            composer = composer,
            defaultBounds = defaultBounds
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
                mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 300)
                mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 700)
            } else {
                restorePanelDefaultSize(host, params)
            }
        }
        windowManager.addView(scrim, handle.scrimParams)
        panelScrimView = scrim
        panelScrimParams = handle.scrimParams

        windowManager.addView(host, params)
        host.viewTreeObserver.addOnGlobalLayoutListener { positionPanelAboveKeyboard(host, params) }
        scrim.viewTreeObserver.addOnGlobalLayoutListener { positionPanelAboveKeyboard(host, params) }
        host.requestFocus()
        panelView = host
        panelParams = params
        // Assume we have focus when we open the panel; the real onWindowFocusChanged
        // callback will correct this if the system never grants focus (e.g., panel
        // opened from a background notification while user is in another app).
        panelHasWindowFocus = true

        renderChatState(lastChatState)
        renderVoiceState(lastVoiceState)
        renderHostConnectionState(lastHostConnectionState)
        renderTranscriptionState(lastTranscriptionState)

        runPanelOpenAnimation(host, scrim, appearancePrefs(), handle.defaultHeight)
        if (suppressNextPanelViewedCallback) {
            suppressNextPanelViewedCallback = false
        } else {
            notifyCurrentChatSessionViewed()
        }
    }

    private fun buildModalHeader(tokens: ThemeTokens, presentation: PanelPresentation): View {
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
            contentDescription = "Open Claw Agent settings",
            viewId = R.id.openclaw_header_settings_button,
            compact = true
        ) {
            dismissPanel()
            openSettings()
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

        val brandedTitle = SpannableString("OpenClaw").apply {
            setSpan(ForegroundColorSpan(tokens.danger), 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
            setPadding(dp(DesignTokens.Spacing.sm), dp(3), dp(DesignTokens.Spacing.sm), dp(3))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.openclaw_bubble_logo)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                hideFromAccessibility()
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                rightMargin = dp(DesignTokens.Spacing.xs)
            })
            addView(TextView(context).apply {
                text = brandedTitle
                textSize = 18f
                setTextColor(tokens.primaryText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                hideFromAccessibility()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(titleChevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
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
            addView(connectionButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(voiceButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(settingsButton, LinearLayout.LayoutParams(headerSize, headerSize).apply { rightMargin = headerGap })
            addView(closeButton, LinearLayout.LayoutParams(headerSize, headerSize))
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
                addView(titleStack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        panelView?.animate()?.cancel()
        panelScrimView?.animate()?.cancel()
        finalizePanelDismiss()
        onStartVoice()
    }

    private fun handlePanelBackPressed() {
        if (isAnchoredPickerShowing()) {
            anchoredPicker?.dismiss()
        } else {
            dismissPanel()
        }
    }

    private fun isAnchoredPickerShowing(): Boolean {
        return anchoredPicker?.isShowing == true
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
            hint = "Message OpenClaw"
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
                maybeShowSlashCommands(this)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!suppressSlashAutocomplete) {
                        maybeShowSlashCommands(this@apply)
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
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
        }

        val controlSize = dp(DesignTokens.Sizes.compact)
        val sendSize = dp(DesignTokens.Sizes.compactAction)
        val controlGap = dp(3)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        plusButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_new_chat,
            contentDescription = "Start new chat",
            viewId = R.id.openclaw_new_chat_button,
            compact = true
        ) { startNewChatSession() }.apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        controls.addView(plusButton, LinearLayout.LayoutParams(controlSize, controlSize).apply { rightMargin = controlGap })

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
            if (lastTranscriptionState.isRecording) {
                onStopTranscription()
                setStatus("Transcribing audio...")
            } else if (lastChatState.isRunning) {
                onStop()
                setStatus("Stop requested")
            } else {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (onSubmit(text)) {
                        input.setText("")
                        setStatus("Sent to OpenClaw")
                    }
                }
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
        onDismiss: (() -> Unit)? = null
    ) {
        val host = panelHost ?: return
        val picker = ensurePicker()
        if (replaceShowing && picker.isShowingFor(anchor)) {
            picker.update(title, sections)
            return
        }
        if (toggleSameAnchor && picker.isShowingFor(anchor)) {
            picker.dismiss()
            return
        }
        picker.show(host, anchor, title, sections, onDismiss = onDismiss)
    }

    private fun renderHostConnectionState(state: HostConnectionState) {
        connectionIndicatorButton?.bind(tokens(), state)
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

    private fun showModelChoices() {
        val anchor = modelButton ?: return
        val merged = mergeModelOptions(lastChatState.models)
        if (merged.isEmpty()) {
            setStatus("No models available.")
            return
        }
        val selectedId = if (lastChatState.selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !isExperimentalLocalModelAvailable()) {
            AgentModelOptions.models.firstOrNull()?.id.orEmpty()
        } else {
            lastChatState.selectedModel.orEmpty()
        }
        val rows = merged.map { model ->
            AnchoredPicker.Row(
                id = "model:${model.id}",
                label = model.label,
                sublabel = model.provider?.takeIf { it.isNotBlank() },
                iconRes = R.drawable.ic_model,
                selected = model.id == selectedId,
                enabled = model.available != false,
                onSelect = {
                    onSetChatModel(model.id)
                    setStatus("Model: ${model.label}")
                }
            )
        }
        showAnchoredPicker(anchor, "Model", listOf(AnchoredPicker.Section(null, rows)))
    }

    private fun isAgentInternalTool(id: String, label: String?): Boolean {
        val needle = (label ?: id).lowercase().trim()
        val rawId = id.lowercase().trim()
        val hiddenExact = setOf(
            "apply_patch", "apply-patch", "applypatch",
            "exec",
            "edit",
            "process",
            "read",
            "session_history", "session-history", "sessionhistory",
            "send",
            "status",
            "list",
            "spawn",
            "session_send", "session-send", "sessionsend",
            "session_status", "session-status", "sessionstatus",
            "session_list", "session-list", "sessionlist",
            "session_spawn", "session-spawn", "sessionspawn",
            "update_plan", "update-plan", "updateplan",
            "web_fetch", "web-fetch", "webfetch",
            "web_search", "web-search", "websearch",
            "subagent", "sub_agent", "sub-agent",
            "subagents", "sub_agents", "sub-agents"
        )
        if (rawId in hiddenExact || needle in hiddenExact) return true
        val hiddenPrefixes = listOf(
            "apply patch",
            "apply_patch",
            "session history",
            "session_history",
            "session send",
            "session_send",
            "session status",
            "session_status",
            "session list",
            "session_list",
            "session spawn",
            "session_spawn",
            "update plan",
            "update_plan",
            "web fetch",
            "web_fetch",
            "web search",
            "web_search",
            "subagent",
            "sub_agent",
            "sub-agent"
        )
        if (hiddenPrefixes.any { needle.startsWith(it) || rawId.startsWith(it) }) return true
        return false
    }

    private fun mergeModelOptions(gatewayModels: List<ChatModelOption>): List<ChatModelOption> {
        val byId = linkedMapOf<String, ChatModelOption>()
        AgentModelOptions.models.forEach { local ->
            byId[local.id] = ChatModelOption(
                id = local.id,
                label = local.label,
                provider = null,
                contextWindow = null,
                available = true
            )
        }
        gatewayModels.forEach { remote ->
            byId[remote.id] = remote
        }
        if (isExperimentalLocalModelAvailable()) {
            byId[AgentModelOptions.LOCAL_LITERT_MODEL_ID] = ChatModelOption(
                id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                label = "Local LiteRT-LM",
                provider = "android",
                contextWindow = null,
                available = true
            )
        } else {
            byId.remove(AgentModelOptions.LOCAL_LITERT_MODEL_ID)
        }
        return byId.values.toList()
    }

    private fun isExperimentalLocalModelAvailable(): Boolean {
        val config = AgentConfigStore.load(context)
        return config.experimentalLocalModelsEnabled && LocalModelStore.exists(config.localModelPath)
    }

    private fun showReasoningChoices() {
        val anchor = reasoningButton ?: return
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
        showAnchoredPicker(anchor, "Reasoning", listOf(AnchoredPicker.Section(null, rows)))
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

    private fun sessionPickerRows(limit: Int = 30): List<AnchoredPicker.Row> {
        return lastChatState.sessions.take(limit).map { session ->
            val label = sessionLabel(session)
            AnchoredPicker.Row(
                id = "session:${session.key}",
                label = label.take(40),
                sublabel = session.model,
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = lastChatState.unreadCountForSession(session.key),
                selected = session.key == lastChatState.sessionKey,
                onSelect = { onSelectChatSession(session.key) }
            )
        }
    }

    private fun startNewChatSession() {
        onNewChatSession()
        composerInput?.setText("")
        setStatus("Started a new chat session")
    }

    private fun showPlusMenu(
        anchorOverride: View? = null,
        replace: Boolean = false,
        onDismiss: (() -> Unit)? = null
    ) {
        val menuAnchor: View = anchorOverride ?: headerSessionAnchor ?: panelContent ?: panelHost ?: return

        val sessions = lastChatState.sessions
        val commands = lastChatState.commands

        val sessionRows = mutableListOf<AnchoredPicker.Row>()
        sessionRows.add(AnchoredPicker.Row(
            id = "chat:new",
            label = "New chat",
            iconRes = R.drawable.ic_new_chat,
            onSelect = { startNewChatSession() }
        ))
        if (sessions.isNotEmpty()) {
            val sessionCount = sessions.size.coerceAtMost(30)
            sessionRows.add(AnchoredPicker.Row(
                id = "chat:previous",
                label = "Previous chats",
                sublabel = "Last $sessionCount",
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = lastChatState.totalUnreadReplies,
                dismissOnSelect = false,
                onSelect = { showSessionsMenu(menuAnchor) }
            ))
        }

        val commandRows = mutableListOf<AnchoredPicker.Row>()
        if (commands.isNotEmpty()) {
            commands.take(20).forEach { command ->
                val text = command.aliases.firstOrNull() ?: "/${command.name}"
                commandRows.add(AnchoredPicker.Row(
                    id = "command:${command.name}",
                    label = text,
                    sublabel = command.description?.take(64),
                    iconRes = R.drawable.ic_command,
                    onSelect = { insertComposerText("$text ") }
                ))
            }
        }

        val modeRows = listOf(
            plusFastModeRow(),
            plusVerboseRow(),
            AnchoredPicker.Row(
                id = "status:refresh",
                label = "Refresh status",
                iconRes = R.drawable.ic_usage,
                onSelect = { onChatControlCommand("status", JSONObject()); setStatus("Refreshing status") }
            )
        )

        val voiceRows = listOf(
            plusReasoningStreamRow(),
            plusToolCallsRow(),
            AnchoredPicker.Row(
                id = "voice:start",
                label = "Voice mode",
                iconRes = R.drawable.ic_voice,
                onSelect = { startVoiceAndMinimizePanel() }
            ),
            AnchoredPicker.Row(
                id = "queue:steer",
                label = "Queue steer",
                iconRes = R.drawable.ic_steer,
                onSelect = { insertComposerText("/queue steer ") }
            ),
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
        if (commandRows.isNotEmpty()) sections.add(AnchoredPicker.Section("Commands", commandRows))
        sections.add(AnchoredPicker.Section("Run mode", modeRows))
        sections.add(AnchoredPicker.Section("More", voiceRows))

        showAnchoredPicker(
            menuAnchor,
            "Menu",
            sections,
            toggleSameAnchor = !replace,
            replaceShowing = replace,
            onDismiss = onDismiss
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
                lastChatState = lastChatState.copy(
                    fastMode = nextEnabled,
                    status = if (nextEnabled) "Fast mode enabled" else "Fast mode disabled"
                )
                renderChatState(lastChatState)
                onChatControlCommand("fast", JSONObject().put("enabled", nextEnabled))
                setStatus(if (nextEnabled) "Fast mode enabled" else "Fast mode disabled")
                updatePlusMenuToggleRow(plusFastModeRow())
            }
        )
    }

    private fun plusVerboseRow(): AnchoredPicker.Row {
        val verboseMode = normalizedVerboseLevel(lastChatState.verboseLevel)
        val nextVerboseMode = nextVerboseLevel(verboseMode)
        return AnchoredPicker.Row(
            id = PLUS_ROW_VERBOSE,
            label = "Verbose: ${verboseMode.replaceFirstChar { it.uppercase() }}",
            sublabel = "Tap for ${nextVerboseMode.replaceFirstChar { it.uppercase() }}",
            iconRes = R.drawable.ic_command,
            selected = verboseMode != "off",
            dismissOnSelect = false,
            onSelect = {
                val nextLevel = nextVerboseLevel(normalizedVerboseLevel(lastChatState.verboseLevel))
                lastChatState = lastChatState.copy(verboseLevel = nextLevel, status = "Verbose: $nextLevel")
                renderChatState(lastChatState)
                onChatControlCommand("verbose", JSONObject().put("level", nextLevel))
                setStatus("Verbose: $nextLevel")
                updatePlusMenuToggleRow(plusVerboseRow())
            }
        )
    }

    private fun plusReasoningStreamRow(): AnchoredPicker.Row {
        val reasoningStreamOn = lastChatState.reasoningStreamEnabled == true
        return AnchoredPicker.Row(
            id = PLUS_ROW_REASONING_STREAM,
            label = "Reasoning Stream: ${if (reasoningStreamOn) "On" else "Off"}",
            sublabel = if (reasoningStreamOn) "Tap to hide reasoning stream" else "Tap to stream reasoning updates",
            iconRes = R.drawable.ic_reasoning,
            selected = reasoningStreamOn,
            dismissOnSelect = false,
            onSelect = {
                toggleReasoningStream()
                updatePlusMenuToggleRow(plusReasoningStreamRow())
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

    private fun toggleReasoningStream() {
        val nextEnabled = lastChatState.reasoningStreamEnabled != true
        lastChatState = lastChatState.copy(
            reasoningStreamEnabled = nextEnabled,
            status = "Reasoning Stream: ${if (nextEnabled) "On" else "Off"}"
        )
        renderChatState(lastChatState)
        onChatControlCommand("reasoning", JSONObject().put("level", if (nextEnabled) "stream" else "off"))
        setStatus("Reasoning Stream: ${if (nextEnabled) "On" else "Off"}")
    }

    private fun normalizedVerboseLevel(level: String?): String {
        return when (level?.lowercase()?.trim()) {
            "on", "full" -> level.lowercase().trim()
            "high", "true" -> "on"
            else -> "off"
        }
    }

    private fun nextVerboseLevel(current: String): String {
        return when (current) {
            "off" -> "on"
            "on" -> "full"
            else -> "off"
        }
    }

    private fun showSessionsMenu(anchorOverride: View? = null) {
        val anchor = anchorOverride ?: headerSessionAnchor ?: panelHost ?: return
        val rows = sessionPickerRows()
        if (rows.isEmpty()) {
            setStatus("No previous chats yet.")
            return
        }
        showAnchoredPicker(
            anchor,
            "Previous chats",
            listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = false,
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

    private fun sessionLabel(session: ChatSessionRow): String {
        return session.displayName ?: session.label ?: session.sessionId ?: session.key.substringAfterLast(":")
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
                label = "Refresh",
                iconRes = R.drawable.ic_bolt,
                onSelect = { onChatControlCommand("status", JSONObject()) }
            )
        )
        showAnchoredPicker(anchor, "Usage", listOf(AnchoredPicker.Section(null, rows)), toggleSameAnchor = false)
    }

    private fun insertComposerText(text: String) {
        val input = composerInput ?: return
        val existing = input.text.toString()
        val separator = if (existing.isBlank() || existing.endsWith(" ")) "" else " "
        val next = existing + separator + text
        input.setText(next)
        input.setSelection(next.length)
    }

    private fun maybeShowSlashCommands(input: EditText) {
        val token = currentSlashToken(input)
        if (token == null) {
            if (anchoredPicker?.isShowingFor(input) == true) {
                anchoredPicker?.dismiss()
            }
            return
        }
        val commands = matchingSlashCommands(token.query)
        if (commands.isEmpty()) {
            if (anchoredPicker?.isShowingFor(input) == true) {
                anchoredPicker?.dismiss()
            }
            return
        }
        val rows = commands.map { command ->
            val text = slashCommandText(command)
            AnchoredPicker.Row(
                id = "slash:${command.name}",
                label = text,
                sublabel = command.description?.take(72),
                iconRes = R.drawable.ic_command,
                onSelect = { autocompleteSlashCommand(input, token, text) }
            )
        }
        showAnchoredPicker(
            anchor = input,
            title = "Commands",
            sections = listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = false
        )
    }

    private fun currentSlashToken(input: EditText): SlashToken? {
        val text = input.text?.toString().orEmpty()
        val cursor = input.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
        val start = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), (cursor - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        if (start >= text.length || text.getOrNull(start) != '/') return null
        val end = cursor
        if (end < start + 1) return null
        val token = text.substring(start, end)
        if (token.drop(1).any { it.isWhitespace() }) return null
        return SlashToken(start = start, end = end, query = token.drop(1))
    }

    private fun matchingSlashCommands(query: String): List<dev.androidagent.chat.ChatCommandOption> {
        val normalized = query.trimStart('/').lowercase()
        return lastChatState.commands
            .filter { command ->
                if (normalized.isBlank()) {
                    true
                } else {
                    command.name.lowercase().startsWith(normalized) ||
                        command.aliases.any { alias -> alias.trimStart('/').lowercase().startsWith(normalized) }
                }
            }
            .take(20)
    }

    private fun slashCommandText(command: dev.androidagent.chat.ChatCommandOption): String {
        return command.aliases.firstOrNull()?.takeIf { it.startsWith("/") } ?: "/${command.name}"
    }

    private fun autocompleteSlashCommand(input: EditText, token: SlashToken, commandText: String) {
        val current = input.text?.toString().orEmpty()
        val safeStart = token.start.coerceIn(0, current.length)
        val safeEnd = token.end.coerceIn(safeStart, current.length)
        val replacement = "$commandText "
        val next = current.replaceRange(safeStart, safeEnd, replacement)
        suppressSlashAutocomplete = true
        input.setText(next)
        input.setSelection((safeStart + replacement.length).coerceAtMost(next.length))
        suppressSlashAutocomplete = false
        input.requestFocus()
    }

    private fun renderChatState(state: ChatState) {
        val tokens = tokens()
        renderComposerActionButtons(tokens, state, lastTranscriptionState)
        modelButton?.let { btn ->
            val fastModeOn = state.fastMode == true
            val modelLabel = formatModelLabel(state.selectedModel ?: state.models.firstOrNull()?.id)
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
            val reasoningLabel = formatReasoningLabel(state.reasoningEffort)
            btn.text = reasoningLabel
            btn.updateAccessibilityState(
                description = "Reasoning selector",
                stateDescription = reasoningLabel
            )
        }
        contextUsageView?.bind(tokens, state.usage.contextRatio)
        statusText?.let { sv ->
            state.status?.let { sv.setText(it) }
            sv.setActive(state.isRunning)
        }
        bubbleOverlay.renderUnreadBadge(state)
        renderTimeline(state)
    }

    private fun notifyCurrentChatSessionViewed() {
        if (!isPanelActivelyViewed()) return
        lastChatState.sessionKey?.takeIf { it.isNotBlank() }?.let(onChatSessionViewed)
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
            notifyCurrentChatSessionViewed()
        } else {
            if (activePanelPresentation == PanelPresentation.Fullscreen && !bubbleOverlay.isVisible) {
                // User backgrounded the fullscreen chat (home, app switcher,
                // another app on top). Bring the bubble back so it can reflect
                // chat state. Preserve the original dismiss-time restore intent
                // so the regular dismiss path stays a no-op for the bubble.
                restoreBubbleAfterFullscreen = true
                showInternal(allowDuringFullscreenPanel = true)
            }
        }
    }

    private fun formatModelLabel(model: String?): String {
        val raw = if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !isExperimentalLocalModelAvailable()) {
            AgentModelOptions.models.firstOrNull()?.id
        } else {
            model
        } ?: return "Model"
        val pretty = lastChatState.models.firstOrNull { it.id == raw }?.label
            ?: raw.substringAfter("/").ifBlank { raw }
        return if (pretty.startsWith("gpt-", ignoreCase = true)) pretty.drop(4) else pretty
    }

    private fun formatReasoningLabel(reasoning: String?): String {
        val value = reasoning?.takeIf { it.isNotBlank() } ?: return "Reason"
        if (value.equals("medium", ignoreCase = true)) return "Med"
        return value.replaceFirstChar { it.uppercase() }
    }

    private fun renderTimeline(state: ChatState) {
        chatTimelineBinder.render(state, showToolCalls)
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
        detachOverlayView(windowManager, panelView)
        detachOverlayView(windowManager, panelScrimView)
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
        chatTimelineBinder.clear()
        composerContainer = null
        keyboardSpacerView = null
        sendStopButton = null
        modelButton = null
        reasoningButton = null
        contextUsageView = null
        headerSessionAnchor = null
        headerSessionChevron = null
        connectionIndicatorButton = null
        plusButton = null
        if (dismissedPresentation == PanelPresentation.Fullscreen) {
            restoreBubbleAfterFullscreenDismiss()
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
            val shouldShowStop = transcriptionState.isRecording || chatState.isRunning
            setImageResource(if (shouldShowStop) R.drawable.ic_stop else R.drawable.ic_send)
            updateAccessibilityState(description = when {
                transcriptionState.isRecording -> "Stop recording and transcribe"
                chatState.isRunning -> "Stop OpenClaw turn"
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
        private const val PLUS_ROW_FAST_MODE = "plus_fast_mode"
        private const val PLUS_ROW_VERBOSE = "plus_verbose"
        private const val PLUS_ROW_REASONING_STREAM = "plus_reasoning_stream"
        private const val PLUS_ROW_TOOL_CALLS = "plus_tool_calls"
        const val MIN_BUBBLE_SIZE_DP = AppearancePrefs.MIN_BUBBLE_SIZE_DP
        const val DEFAULT_BUBBLE_SIZE_DP = AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP
        const val MAX_BUBBLE_SIZE_DP = AppearancePrefs.MAX_BUBBLE_SIZE_DP
    }

    private fun openSettings() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_SETTINGS, true)
        )
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

    private fun keepAboveKeyboard(view: View, params: WindowManager.LayoutParams) {
        positionPanelAboveKeyboard(view, params)
    }

    private fun positionPanelAboveKeyboard(panel: View, params: WindowManager.LayoutParams) {
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
        val keyboardTop = if (visibleFrameKeyboardTop != null) {
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
        if (defaultBottom - keyboardTop < dp(120)) {
            restorePanelDefaultSize(panel, params)
            return
        }

        panel.translationY = 0f
        if (activePanelPresentation == PanelPresentation.Fullscreen) {
            setKeyboardSpacerHeight(
                PanelKeyboardLayout.fullscreenKeyboardSpacerHeight(
                    defaultBounds = defaultBounds,
                    keyboardTop = keyboardTop,
                    bottomClearance = keyboardBottomClearance()
                )
            )
            if (params.height != defaultBounds.height || params.y != defaultBounds.y) {
                params.height = defaultBounds.height
                params.y = defaultBounds.y
                windowManager.updateViewLayout(panel, params)
            }
            anchoredPicker?.reposition()
            return
        }

        setKeyboardSpacerHeight(keyboardBottomClearance())
        val minPanelHeight = dp(300)
        val adjustedBounds = PanelKeyboardLayout.adjustedBoundsAboveKeyboard(
            defaultBounds = defaultBounds,
            keyboardTop = keyboardTop,
            minPanelHeight = minPanelHeight,
            minY = dp(8),
            composerGap = keyboardComposerGap(),
            minHeight = dp(240)
        ) ?: run {
            restorePanelDefaultSize(panel, params)
            return
        }
        if (params.height != adjustedBounds.height || params.y != adjustedBounds.y) {
            params.height = adjustedBounds.height
            params.y = adjustedBounds.y
            windowManager.updateViewLayout(panel, params)
            anchoredPicker?.reposition()
        }
        anchoredPicker?.reposition()
    }

    private fun keyboardTopFromVisibleFrame(defaultPanelBottom: Int): Int? {
        val scrim = panelScrimView ?: return null
        if (!isOverlayAttached(scrim)) {
            return null
        }
        val visible = Rect()
        scrim.getWindowVisibleDisplayFrame(visible)
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
        presentation: PanelPresentation = activePanelPresentation
    ): PanelBounds {
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
