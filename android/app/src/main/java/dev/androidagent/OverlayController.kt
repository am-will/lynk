package dev.androidagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.view.isNotEmpty
import dev.androidagent.avatar.AvatarConfigStore
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.avatar.AvatarSelection
import dev.androidagent.avatar.PetAnimation
import dev.androidagent.avatar.PetAvatarView
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.chat.ChatTimelineKind
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatTimelineItem
import dev.androidagent.chat.ChatTimelineRenderer
import dev.androidagent.overlay.HostConnectionCopy
import dev.androidagent.overlay.HostConnectionPhase
import dev.androidagent.overlay.HostConnectionState
import dev.androidagent.overlay.PanelPresentation
import dev.androidagent.overlay.hostConnectionColor
import dev.androidagent.ui.AnchoredPicker
import dev.androidagent.ui.ClipboardHelper
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.MarkdownFencedCodeChunk
import dev.androidagent.ui.MarkdownFencedCodeParser
import dev.androidagent.ui.MarkdownRenderer
import dev.androidagent.ui.StatusUpdateView
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import org.json.JSONObject
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.VoiceRuntimeStatus
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
    private val trashShowInterpolator = DecelerateInterpolator()
    private val trashHideInterpolator = AccelerateInterpolator()
    private var bubbleView: View? = null
    private var bubbleUnreadBadgeView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubblePulseAnimator: AnimatorSet? = null
    private var lastBubbleX: Int? = null
    private var lastBubbleY: Int? = null
    private var bubblePetView: PetAvatarView? = null
    private var bubbleLastDragX: Int? = null
    private var bubbleLastDragSampleMs: Long = 0L
    private var bubbleHasUnread: Boolean = false
    private var bubbleIsWorking: Boolean = false
    private var bubbleIsDragging: Boolean = false
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelScrimView: View? = null
    private var panelScrimParams: WindowManager.LayoutParams? = null
    private var trashTargetView: ImageView? = null
    private var trashTargetBounds = Rect()
    private var isBubbleOverTrashTarget = false
    private var isDismissAnimating = false
    private var confirmationView: View? = null
    private var confirmationScrimView: View? = null
    private var statusText: StatusUpdateView? = null
    private var voiceSurface: LinearLayout? = null
    private var voiceStatusText: TextView? = null
    private var voiceTranscriptText: TextView? = null
    private var voiceTaskText: TextView? = null
    private var voiceResultText: TextView? = null
    private var voiceMuteButton: Button? = null
    private var voiceHangupButton: Button? = null
    private var lastVoiceState = VoiceRuntimeState()
    private var voiceSurfaceForceHidden = false
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
    private var historyContainer: LinearLayout? = null
    private var historyScrollView: ScrollView? = null
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

    private data class PanelBounds(val height: Int, val y: Int)
    private data class SlashToken(val start: Int, val end: Int, val query: String)

    fun show() {
        showInternal(allowDuringFullscreenPanel = false)
    }

    private fun showInternal(allowDuringFullscreenPanel: Boolean) {
        if (
            !Settings.canDrawOverlays(context) ||
            bubbleView != null ||
            automationSuppressionDepth > 0 ||
            (!allowDuringFullscreenPanel && isFullscreenPanelAttached())
        ) {
            return
        }
        val tokens = tokens()
        val badge = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFFE53935.toInt())
            }
            minWidth = dp(20)
            minHeight = dp(20)
            includeFontPadding = false
            setPadding(dp(4), 0, dp(4), 0)
        }
        val avatarView = buildBubbleAvatarView()
        val bubble = FrameLayout(context).apply {
            background = bubbleBackgroundForVoiceState(lastVoiceState, tokens)
            contentDescription = "Open Claw Agent"
            elevation = dp(DesignTokens.Elevation.mid).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { togglePanel(PanelPresentation.Popup) }
            addView(avatarView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(20),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        bubbleUnreadBadgeView = badge
        val initialBubbleDp = AppearancePrefsStore.load(context).bubbleSizeDp
            .coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP)
        val params = overlayParams(width = dp(initialBubbleDp), height = dp(initialBubbleDp), focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastBubbleX ?: dp(16)
            y = lastBubbleY ?: dp(160)
        }
        ensureTrashTarget()
        attachDrag(
            view = bubble,
            params = params,
            onDragStart = {
                showTrashTarget()
                bubbleIsDragging = true
                bubbleLastDragX = params.x
                bubbleLastDragSampleMs = System.currentTimeMillis()
            },
            onDrag = { dragParams, dragView ->
                updateBubbleAvatarForDrag(dragParams.x)
                updateTrashTargetState(dragParams, dragView)
            },
            onDragEnd = { dragParams, dragView ->
                bubbleIsDragging = false
                bubbleLastDragX = null
                applyBubbleRestingState()
                val shouldDismiss = updateTrashTargetState(dragParams, dragView)
                if (shouldDismiss) {
                    dismissPanel(cancelTranscription = false)
                    animateBubbleDismiss(dragView)
                } else {
                    hideTrashTarget()
                }
            },
            onDragCancel = {
                bubbleIsDragging = false
                bubbleLastDragX = null
                applyBubbleRestingState()
                hideTrashTarget()
            }
        ) { togglePanel(PanelPresentation.Popup) }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
        applyBubbleVoiceIndicator(lastVoiceState)
        bubbleIsWorking = lastChatState.isRunning
        renderBubbleUnreadBadge(lastChatState)
        applyBubbleRestingState()
    }

    fun hide() {
        automationSuppressionDepth = 0
        restoreBubbleAfterAutomation = false
        restoreBubbleAfterFullscreen = false
        rememberBubblePosition()
        stopBubblePulse()
        bubbleView?.let { windowManager.removeView(it) }
        removeTrashTarget()
        dismissPanel()
        dismissConfirmation()
        bubbleView = null
        bubbleUnreadBadgeView = null
        bubbleParams = null
        bubblePetView = null
        bubbleLastDragX = null
    }

    fun suppressAgentChromeForAutomation() {
        automationSuppressionDepth += 1
        if (automationSuppressionDepth > 1) {
            return
        }

        restoreBubbleAfterAutomation = bubbleView != null
        restorePanelAfterAutomation = isOverlayAttached(panelView)
        restorePanelScrimAfterAutomation = isOverlayAttached(panelScrimView)
        restorePanelFocusAfterAutomation = panelView?.hasFocus() == true
        restoreComposerFocusAfterAutomation = composerInput?.hasFocus() == true
        rememberBubblePosition()
        // Automation suppression only clears our chrome; it must not stop turns,
        // hang up voice, clear the chat modal's state, or dismiss the foreground service.
        detachOverlayView(panelView)
        detachOverlayView(panelScrimView)
        stopBubblePulse()
        bubbleView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(it)
        }
        bubbleView = null
        bubbleUnreadBadgeView = null
        bubbleParams = null
        bubblePetView = null
        bubbleLastDragX = null
        removeTrashTarget()
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
            bubbleIsWorking = state.isRunning
            renderChatState(state)
            renderBubbleUnreadBadge(state)
            applyBubbleRestingState()
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
        return isOverlayAttached(bubbleView)
    }

    /**
     * Rebuilds the bubble's inner avatar view in-place so changes from the
     * Avatar picker take effect immediately, without waiting for the bubble
     * to be torn down and re-shown.
     */
    fun refreshBubbleAvatar() {
        mainHandler.post {
            val bubble = bubbleView as? FrameLayout ?: return@post
            val badge = bubbleUnreadBadgeView
            bubble.removeAllViews()
            bubblePetView = null
            bubble.addView(buildBubbleAvatarView(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            if (badge != null) {
                bubble.addView(badge, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    dp(20),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
            renderBubbleUnreadBadge(lastChatState)
            applyBubbleRestingState()
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
            val clamped = targetDp.coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP)
            val bubble = bubbleView ?: return@post
            val params = bubbleParams ?: return@post
            val newSizePx = dp(clamped)
            if (params.width == newSizePx && params.height == newSizePx) return@post
            params.width = newSizePx
            params.height = newSizePx
            // Clamp using the freshly-set width/height instead of view.width
            // because the view has not been re-laid out yet.
            val display = context.resources.displayMetrics
            val horizontalInset = dp(8)
            val maxX = (display.widthPixels - newSizePx - horizontalInset).coerceAtLeast(horizontalInset)
            val maxY = (display.heightPixels - newSizePx - dp(8)).coerceAtLeast(dp(8))
            params.x = params.x.coerceIn(horizontalInset, maxX)
            params.y = params.y.coerceIn(dp(8), maxY)
            runCatching { windowManager.updateViewLayout(bubble, params) }
            rememberBubblePosition()
        }
    }

    fun openChatPanel(
        markCurrentSessionViewed: Boolean = true,
        presentation: PanelPresentation = PanelPresentation.Popup
    ) {
        mainHandler.post {
            if (panelView == null) {
                if (bubbleView == null && presentation == PanelPresentation.Popup) {
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
        val deferred = CompletableDeferred<Boolean>()
        if (!Settings.canDrawOverlays(context)) {
            deferred.complete(false)
            return deferred
        }
        dismissConfirmation()
        val tokens = tokens()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(DesignTokens.Spacing.xxl),
                dp(DesignTokens.Spacing.xxl),
                dp(DesignTokens.Spacing.xxl),
                dp(DesignTokens.Spacing.lg)
            )
            background = Drawables.glassPanel(context, tokens, DesignTokens.Radius.xl)
            elevation = dp(DesignTokens.Elevation.popover).toFloat()
        }

        val warningBadge = TextView(context).apply {
            text = "Confirm"
            Typography.applyOverline(this, tokens)
            setTextColor(tokens.accent)
            background = Drawables.accentSoftSurface(context, tokens)
            setPadding(dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.xs), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.xs))
        }
        card.addView(warningBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        card.addView(TextView(context).apply {
            text = "Are you sure?"
            Typography.applyTitle(this, tokens)
            setPadding(0, dp(DesignTokens.Spacing.md), 0, 0)
        })

        card.addView(TextView(context).apply {
            text = listOfNotNull(message, preview).joinToString("\n\n")
            Typography.applyBody(this, tokens, secondary = true)
            setPadding(0, dp(DesignTokens.Spacing.sm), 0, dp(DesignTokens.Spacing.lg))
            setLineSpacing(dp(DesignTokens.Spacing.xs).toFloat(), 1.0f)
        })

        val cancelButton = Button(context).apply {
            text = "Cancel"
            textSize = DesignTokens.Text.callout
            isAllCaps = false
            setTextColor(tokens.primaryText)
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(false)
            }
        }
        val allowButton = Button(context).apply {
            text = "Allow"
            textSize = DesignTokens.Text.callout
            isAllCaps = false
            setTextColor(tokens.accentInk)
            background = Drawables.accentSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(true)
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f).apply { rightMargin = dp(DesignTokens.Spacing.sm) })
            addView(allowButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f))
        }
        card.addView(buttons)

        val scrim = View(context).apply {
            setBackgroundColor(tokens.scrim)
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(false)
            }
        }
        val scrimParams = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(scrim, scrimParams)
        confirmationScrimView = scrim

        val cardWidth = (context.resources.displayMetrics.widthPixels - dp(DesignTokens.Spacing.xxl * 2))
            .coerceAtMost(dp(360))
        val params = overlayParams(width = cardWidth, height = WindowManager.LayoutParams.WRAP_CONTENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        windowManager.addView(card, params)
        confirmationView = card
        return deferred
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
        val history = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.md))
            clipToPadding = false
        }
        historyContainer = history
        val historyScroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
            addView(history)
        }
        historyScrollView = historyScroll
        statusText = StatusUpdateView(context, tokens).apply {
            setText(lastChatState.status ?: "OpenClaw chat ready.")
            setActive(lastChatState.isRunning)
        }
        val voice = buildVoiceSurface(tokens)
        val composer = buildComposer(tokens, input)
        val keyboardSpacer = View(context).apply {
            visibility = View.GONE
            keyboardSpacerView = this
        }
        val header = buildModalHeader(tokens, presentation)

        val chrome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            background = Drawables.glassPanel(context, tokens)
            elevation = dp(DesignTokens.Elevation.popover).toFloat()
            setPadding(0, 0, 0, 0)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handlePanelBackPressed()
                    true
                } else {
                    false
                }
            }
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(voice, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(DesignTokens.Spacing.md)
                rightMargin = dp(DesignTokens.Spacing.md)
                topMargin = dp(DesignTokens.Spacing.xs)
            })
            addView(historyScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(statusText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(composer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(DesignTokens.Spacing.md)
                rightMargin = dp(DesignTokens.Spacing.md)
                bottomMargin = dp(DesignTokens.Spacing.md)
                topMargin = dp(DesignTokens.Spacing.xs)
            })
            addView(keyboardSpacer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ))
        }
        panelContent = chrome

        val host = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if (isModalCloseHotZone(event)) {
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        handlePanelBackPressed()
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            }

            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handlePanelBackPressed()
                    return true
                }
                return super.dispatchKeyEventPreIme(event)
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                handlePanelWindowFocusChanged(hasWindowFocus)
            }
        }.apply {
            isFocusableInTouchMode = true
            addView(chrome, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        panelHost = host

        val display = context.resources.displayMetrics
        val defaultBounds = panelDefaultBounds(display.heightPixels, presentation)
        val params = overlayParams(
            width = display.widthPixels,
            height = defaultBounds.height,
            focusable = true
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = defaultBounds.y
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                armKeyboardFallback()
                mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 300)
                mainHandler.postDelayed({ keepAboveKeyboard(host, params) }, 700)
            } else {
                restorePanelDefaultSize(host, params)
            }
        }
        val scrim = View(context).apply {
            setBackgroundColor(tokens.scrim)
            setOnClickListener { dismissPanel() }
        }
        val scrimParams = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(scrim, scrimParams)
        panelScrimView = scrim
        panelScrimParams = scrimParams

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

        runPanelOpenAnimation(host, scrim, appearancePrefs(), defaultBounds.height)
        if (suppressNextPanelViewedCallback) {
            suppressNextPanelViewedCallback = false
        } else {
            notifyCurrentChatSessionViewed()
        }
    }

    private fun buildModalHeader(tokens: ThemeTokens, presentation: PanelPresentation): View {
        val connectionButton = HostConnectionIndicatorButton(context).apply {
            bind(tokens, lastHostConnectionState)
            setOnClickListener { showHostConnectionPopup(this) }
        }
        connectionIndicatorButton = connectionButton
        val voiceButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_voice_wave,
            contentDescription = "Start realtime voice mode",
            compact = true
        ) {
            startVoiceAndMinimizePanel()
        }
        val settingsButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_settings_gear,
            contentDescription = "Open Claw Agent settings",
            compact = true
        ) {
            dismissPanel()
            openSettings()
        }
        val closeButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_close,
            contentDescription = "Close chat",
            compact = true
        ) { handlePanelBackPressed() }

        val handleArea = if (presentation == PanelPresentation.Popup) {
            val handle = View(context).apply {
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
            headerSessionChevron = this
        }
        val titleStack = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            contentDescription = "Show previous OpenClaw chats"
            setPadding(dp(DesignTokens.Spacing.sm), dp(3), dp(DesignTokens.Spacing.sm), dp(3))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.openclaw_bubble_logo)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(titleChevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                leftMargin = dp(DesignTokens.Spacing.xs)
            })
            setOnClickListener { showHeaderSessionsMenu() }
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
        if (anchoredPicker?.isShowing == true) {
            anchoredPicker?.dismiss()
        } else {
            dismissPanel()
        }
    }

    private fun isModalCloseHotZone(event: android.view.MotionEvent): Boolean {
        if (anchoredPicker?.isShowing == true) {
            return false
        }
        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN &&
            event.actionMasked != android.view.MotionEvent.ACTION_UP
        ) {
            return false
        }
        val host = panelHost ?: return false
        val closeZoneWidth = dp(MODAL_CLOSE_HOT_ZONE_WIDTH_DP)
        val closeZoneHeight = dp(MODAL_CLOSE_HOT_ZONE_HEIGHT_DP)
        return event.x >= host.width - closeZoneWidth && event.y <= closeZoneHeight
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
            drawableRes = R.drawable.ic_plus,
            contentDescription = "Open chat controls",
            compact = true
        ) { showPlusMenu() }.apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        controls.addView(plusButton, LinearLayout.LayoutParams(controlSize, controlSize).apply { rightMargin = controlGap })

        modelButton = compactPill(tokens, "Model", R.drawable.ic_model).apply {
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
            bind(tokens, lastChatState.usage.contextRatio)
            setOnClickListener { showUsageControls() }
        }
        controls.addView(contextUsageView, LinearLayout.LayoutParams(controlSize, controlSize).apply { rightMargin = controlGap })

        controls.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))

        transcriptionMicButton = iconButton(
            tokens = tokens,
            drawableRes = R.drawable.ic_mic,
            contentDescription = "Start voice transcription"
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
            this.contentDescription = contentDescription
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
        val selectedId = lastChatState.selectedModel ?: ""
        val rows = merged.map { model ->
            AnchoredPicker.Row(
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
        return byId.values.toList()
    }

    private fun showReasoningChoices() {
        val anchor = reasoningButton ?: return
        val options = lastChatState.reasoningOptions.ifEmpty { ChatState.defaultReasoningOptions }
        val rows = options.map { option ->
            AnchoredPicker.Row(
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

    private fun showHeaderSessionsMenu() {
        val anchor = headerSessionAnchor ?: panelHost ?: return
        val rows = sessionPickerRows()
        if (rows.isEmpty()) {
            animateHeaderSessionChevron(expanded = false)
            setStatus("No previous chats yet.")
            return
        }

        if (anchoredPicker?.isShowingFor(anchor) != true) {
            animateHeaderSessionChevron(expanded = true)
        }
        showAnchoredPicker(
            anchor,
            "Previous chats",
            listOf(AnchoredPicker.Section(null, rows)),
            toggleSameAnchor = true,
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
                label = label.take(40),
                sublabel = session.model,
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = lastChatState.unreadCountForSession(session.key),
                selected = session.key == lastChatState.sessionKey,
                onSelect = { onSelectChatSession(session.key) }
            )
        }
    }

    private fun showPlusMenu(replace: Boolean = false) {
        val plusAnchor: View = plusButton ?: panelContent ?: panelHost ?: return

        val sessions = lastChatState.sessions
        val commands = lastChatState.commands

        val sessionRows = mutableListOf<AnchoredPicker.Row>()
        sessionRows.add(AnchoredPicker.Row(
            label = "New chat",
            iconRes = R.drawable.ic_plus,
            onSelect = {
                onNewChatSession()
                composerInput?.setText("")
                setStatus("Started a new chat session")
            }
        ))
        if (sessions.isNotEmpty()) {
            val sessionCount = sessions.size.coerceAtMost(30)
            sessionRows.add(AnchoredPicker.Row(
                label = "Previous chats",
                sublabel = "Last $sessionCount",
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = lastChatState.totalUnreadReplies,
                dismissOnSelect = false,
                onSelect = { showSessionsMenu() }
            ))
        }

        val commandRows = mutableListOf<AnchoredPicker.Row>()
        if (commands.isNotEmpty()) {
            commands.take(20).forEach { command ->
                val text = command.aliases.firstOrNull() ?: "/${command.name}"
                commandRows.add(AnchoredPicker.Row(
                    label = text,
                    sublabel = command.description?.take(64),
                    iconRes = R.drawable.ic_command,
                    onSelect = { insertComposerText("$text ") }
                ))
            }
        }

        val fastModeOn = lastChatState.fastMode == true
        val verboseMode = normalizedVerboseLevel(lastChatState.verboseLevel)
        val nextVerboseMode = nextVerboseLevel(verboseMode)
        val reasoningStreamOn = lastChatState.reasoningStreamEnabled == true
        val modeRows = listOf(
            AnchoredPicker.Row(
                label = "Fast mode: ${if (fastModeOn) "On" else "Off"}",
                sublabel = if (fastModeOn) "Tap to turn off" else "Tap to turn on",
                iconRes = R.drawable.ic_bolt,
                selected = fastModeOn,
                dismissOnSelect = false,
                onSelect = {
                    val nextEnabled = !fastModeOn
                    lastChatState = lastChatState.copy(fastMode = nextEnabled, status = if (nextEnabled) "Fast mode enabled" else "Fast mode disabled")
                    renderChatState(lastChatState)
                    onChatControlCommand("fast", JSONObject().put("enabled", nextEnabled))
                    setStatus(if (nextEnabled) "Fast mode enabled" else "Fast mode disabled")
                    showPlusMenu(replace = true)
                }
            ),
            AnchoredPicker.Row(
                label = "Verbose: ${verboseMode.replaceFirstChar { it.uppercase() }}",
                sublabel = "Tap for ${nextVerboseMode.replaceFirstChar { it.uppercase() }}",
                iconRes = R.drawable.ic_command,
                selected = verboseMode != "off",
                dismissOnSelect = false,
                onSelect = {
                    lastChatState = lastChatState.copy(verboseLevel = nextVerboseMode, status = "Verbose: $nextVerboseMode")
                    renderChatState(lastChatState)
                    onChatControlCommand("verbose", JSONObject().put("level", nextVerboseMode))
                    setStatus("Verbose: $nextVerboseMode")
                    showPlusMenu(replace = true)
                }
            ),
            AnchoredPicker.Row(
                label = "Refresh status",
                iconRes = R.drawable.ic_usage,
                onSelect = { onChatControlCommand("status", JSONObject()); setStatus("Refreshing status") }
            )
        )

        val voiceRows = listOf(
            AnchoredPicker.Row(
                label = "Reasoning Stream: ${if (reasoningStreamOn) "On" else "Off"}",
                sublabel = if (reasoningStreamOn) "Tap to hide reasoning stream" else "Tap to stream reasoning updates",
                iconRes = R.drawable.ic_reasoning,
                selected = reasoningStreamOn,
                dismissOnSelect = false,
                onSelect = {
                    toggleReasoningStream()
                    showPlusMenu(replace = true)
                }
            ),
            AnchoredPicker.Row(
                label = "Show Tool Calls: ${if (showToolCalls) "On" else "Off"}",
                sublabel = if (showToolCalls) "Tap to hide bash, MCP, web search, and other tool activity" else "Tap to show tool activity",
                iconRes = R.drawable.ic_tools,
                selected = showToolCalls,
                dismissOnSelect = false,
                onSelect = {
                    showToolCalls = !showToolCalls
                    setStatus("Tool calls ${if (showToolCalls) "shown" else "hidden"}")
                    renderTimeline(lastChatState)
                    showPlusMenu(replace = true)
                }
            ),
            AnchoredPicker.Row(
                label = "Voice mode",
                iconRes = R.drawable.ic_voice,
                onSelect = { startVoiceAndMinimizePanel() }
            ),
            AnchoredPicker.Row(
                label = "Queue steer",
                iconRes = R.drawable.ic_steer,
                onSelect = { insertComposerText("/queue steer ") }
            ),
            AnchoredPicker.Row(
                label = "Usage",
                iconRes = R.drawable.ic_usage,
                dismissOnSelect = false,
                onSelect = { showUsageControls() }
            ),
            AnchoredPicker.Row(
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
            plusAnchor,
            "Menu",
            sections,
            toggleSameAnchor = !replace,
            replaceShowing = replace
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

    private fun showSessionsMenu() {
        val anchor = plusButton ?: panelHost ?: return
        val rows = sessionPickerRows()
        if (rows.isEmpty()) {
            setStatus("No previous chats yet.")
            return
        }
        showAnchoredPicker(anchor, "Previous chats", listOf(AnchoredPicker.Section(null, rows)), toggleSameAnchor = false)
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
                label = "Context",
                sublabel = percent,
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                label = "Total tokens",
                sublabel = (usage.totalTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                label = "Input tokens",
                sublabel = (usage.inputTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
                label = "Output tokens",
                sublabel = (usage.outputTokens ?: "--").toString(),
                iconRes = R.drawable.ic_usage,
                enabled = false,
                onSelect = {}
            ),
            AnchoredPicker.Row(
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
            btn.text = formatModelLabel(state.selectedModel ?: state.models.firstOrNull()?.id)
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
        reasoningButton?.text = formatReasoningLabel(state.reasoningEffort)
        contextUsageView?.bind(tokens, state.usage.contextRatio)
        statusText?.let { sv ->
            state.status?.let { sv.setText(it) }
            sv.setActive(state.isRunning)
        }
        renderBubbleUnreadBadge(state)
        renderTimeline(state)
    }

    private fun renderBubbleUnreadBadge(state: ChatState) {
        val count = state.totalUnreadReplies
        bubbleHasUnread = count > 0
        applyBubbleRestingState()
        val badge = bubbleUnreadBadgeView ?: return
        if (count <= 0) {
            badge.visibility = View.GONE
            badge.text = ""
            bubbleView?.contentDescription = "Open Claw Agent"
            return
        }
        badge.text = badgeText(count)
        badge.visibility = View.VISIBLE
        bubbleView?.contentDescription = "Open Claw Agent, $count unread replies"
    }

    private fun applyBubbleRestingState() {
        if (bubbleIsDragging) return
        val pet = bubblePetView ?: return
        val resting = when {
            bubbleIsWorking -> PetAnimation.State.Review
            bubbleHasUnread -> PetAnimation.State.Jumping
            else -> PetAnimation.State.Idle
        }
        pet.setState(resting)
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
            if (activePanelPresentation == PanelPresentation.Fullscreen && bubbleView == null) {
                // User backgrounded the fullscreen chat (home, app switcher,
                // another app on top). Bring the bubble back so it can reflect
                // chat state. Preserve the original dismiss-time restore intent
                // so the regular dismiss path stays a no-op for the bubble.
                restoreBubbleAfterFullscreen = true
                showInternal(allowDuringFullscreenPanel = true)
            }
        }
    }

    private fun badgeText(count: Int): String {
        return if (count > 99) "99+" else count.toString()
    }

    private fun formatModelLabel(model: String?): String {
        val raw = model ?: return "Model"
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
        val container = historyContainer ?: return
        val tokens = tokens()
        container.removeAllViews()
        val plan = ChatTimelineRenderer.plan(state, showToolCalls)
        if (plan.isEmpty) {
            container.addView(emptyHistoryView(tokens))
        } else {
            plan.items.forEach { item ->
                container.addView(when (item.kind) {
                    ChatTimelineKind.MESSAGE -> messageBubble(item, tokens)
                    ChatTimelineKind.TOOL -> toolRow(item, tokens)
                    ChatTimelineKind.REASONING -> reasoningBlock(item, tokens)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(DesignTokens.Spacing.sm) })
            }
        }
        historyScrollView?.post { historyScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun reasoningBlock(item: ChatTimelineItem, tokens: ThemeTokens): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.accentSoftSurface(context, tokens, DesignTokens.Radius.lg)
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm)
            )
            addView(TextView(context).apply {
                text = "Reasoning Stream"
                Typography.applyCaption(this, tokens, emphasis = true)
                setTextColor(tokens.accent)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_reasoning, 0, 0, 0)
                compoundDrawablePadding = dp(DesignTokens.Spacing.xs)
            })
            addView(TextView(context).apply {
                text = item.text.ifBlank { if (item.isStreaming) "Thinking..." else "" }
                Typography.applyCallout(this, tokens)
                setTextColor(tokens.primaryText)
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(DesignTokens.Spacing.xs) })
        }
    }

    private fun emptyHistoryView(tokens: ThemeTokens): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(DesignTokens.Spacing.xxl), dp(48), dp(DesignTokens.Spacing.xxl), dp(48))

            addView(ImageView(context).apply {
                setImageResource(R.drawable.openclaw_bubble_logo)
                alpha = 0.65f
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

            addView(TextView(context).apply {
                text = "Start a conversation"
                Typography.applyTitle(this, tokens)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(DesignTokens.Spacing.lg)
                gravity = Gravity.CENTER_HORIZONTAL
            })

            addView(TextView(context).apply {
                text = "OpenClaw is ready. Say something or pick a previous chat from the title menu."
                Typography.applyBody(this, tokens, secondary = true)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(DesignTokens.Spacing.sm)
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    private fun messageBubble(item: ChatTimelineItem, tokens: ThemeTokens): View {
        val role = item.role
        val isLocalStatusNotice = role == "system" && item.id.startsWith("system_")
        if (role == "system" && !isLocalStatusNotice) {
            return TextView(context).apply {
                text = item.text.ifBlank { "Status" }
                Typography.applyFootnote(this, tokens, secondary = true)
                gravity = Gravity.CENTER
                setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.sm), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.sm))
                background = Drawables.chatBubbleSystem(context, tokens)
                setTextColor(tokens.bubbleSystemInk)
                attachMessageCopyGesture(this, item.text)
            }
        }

        val isUser = role == "user"
        val isAssistant = role == "assistant"
        val isStreaming = !isUser && item.isStreaming && item.text.isBlank()

        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val bubble = if (isAssistant && !isStreaming) {
            val chunks = MarkdownFencedCodeParser.parse(item.text)
            if (chunks.any { it is MarkdownFencedCodeChunk.CodeBlock }) {
                assistantMessageBubbleWithCodeBlocks(
                    messageText = item.text,
                    chunks = chunks,
                    tokens = tokens,
                    maxWidth = maxWidth
                )
            } else {
                plainMessageBubble(
                    messageText = item.text,
                    tokens = tokens,
                    isUser = false,
                    isStreaming = false,
                    maxWidth = maxWidth
                )
            }
        } else {
            plainMessageBubble(
                messageText = item.text,
                tokens = tokens,
                isUser = isUser,
                isStreaming = isStreaming,
                maxWidth = maxWidth
            )
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = if (isUser) Gravity.END else Gravity.START
            })
        }
    }

    private fun plainMessageBubble(
        messageText: String,
        tokens: ThemeTokens,
        isUser: Boolean,
        isStreaming: Boolean,
        maxWidth: Int
    ): TextView {
        return TextView(context).apply {
            Typography.applyCallout(this, tokens)
            setTextColor(if (isUser) tokens.bubbleUserInk else tokens.bubbleAssistantInk)
            setLinkTextColor(tokens.accent)
            setPadding(
                dp(DesignTokens.Spacing.md + 2),
                dp(DesignTokens.Spacing.sm + 2),
                dp(DesignTokens.Spacing.md + 2),
                dp(DesignTokens.Spacing.sm + 2)
            )
            setLineSpacing(dp(DesignTokens.Spacing.xs).toFloat(), 1.0f)
            background = if (isUser) Drawables.chatBubbleUser(context, tokens)
                else Drawables.chatBubbleAssistant(context, tokens)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            this.maxWidth = maxWidth
            if (isStreaming) {
                text = "•  •  •"
                animateStreamingDots(this)
            } else if (isUser) {
                text = messageText
            } else {
                MarkdownRenderer.render(this, messageText, tokens)
            }
            attachMessageCopyGesture(this, messageText, enabled = !isStreaming)
        }
    }

    private fun assistantMessageBubbleWithCodeBlocks(
        messageText: String,
        chunks: List<MarkdownFencedCodeChunk>,
        tokens: ThemeTokens,
        maxWidth: Int
    ): LinearLayout {
        val horizontalPadding = dp(DesignTokens.Spacing.md + 2)
        val childMaxWidth = (maxWidth - (horizontalPadding * 2)).coerceAtLeast(dp(120))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                horizontalPadding,
                dp(DesignTokens.Spacing.sm + 2),
                horizontalPadding,
                dp(DesignTokens.Spacing.sm + 2)
            )
            background = Drawables.chatBubbleAssistant(context, tokens)
            attachMessageCopyGesture(this, messageText)

            chunks.forEach { chunk ->
                val child = when (chunk) {
                    is MarkdownFencedCodeChunk.Prose -> proseChunkView(chunk.text, tokens, childMaxWidth).also {
                        attachMessageCopyGesture(it, messageText)
                    }
                    is MarkdownFencedCodeChunk.CodeBlock -> codeBlockChunkView(chunk, tokens, messageText, childMaxWidth)
                }
                addChunkView(child)
            }
        }
    }

    private fun proseChunkView(
        text: String,
        tokens: ThemeTokens,
        maxWidth: Int
    ): TextView {
        return TextView(context).apply {
            Typography.applyCallout(this, tokens)
            setTextColor(tokens.bubbleAssistantInk)
            setLinkTextColor(tokens.accent)
            setLineSpacing(dp(DesignTokens.Spacing.xs).toFloat(), 1.0f)
            this.maxWidth = maxWidth
            MarkdownRenderer.render(this, text, tokens)
        }
    }

    private fun codeBlockChunkView(
        chunk: MarkdownFencedCodeChunk.CodeBlock,
        tokens: ThemeTokens,
        messageText: String,
        maxWidth: Int
    ): TextView {
        return TextView(context).apply {
            Typography.applyMono(this, tokens)
            setTextColor(tokens.bubbleAssistantInk)
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm)
            )
            setLineSpacing(dp(DesignTokens.Spacing.xs).toFloat(), 1.0f)
            background = Drawables.rippleOver(
                context,
                tokens,
                Drawables.glassInset(context, tokens, DesignTokens.Radius.sm)
            )
            this.maxWidth = maxWidth
            minWidth = dp(96)
            text = chunk.code.ifEmpty { " " }
            setOnClickListener {
                ClipboardHelper.copyCodeBlock(context, chunk.copyText)
            }
            attachMessageCopyGesture(this, messageText)
        }
    }

    private fun LinearLayout.addChunkView(child: View) {
        val hasPreviousChunk = isNotEmpty()
        addView(child, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (hasPreviousChunk) {
                topMargin = dp(DesignTokens.Spacing.sm)
            }
        })
    }

    private fun attachMessageCopyGesture(
        view: View,
        messageText: String,
        enabled: Boolean = true
    ) {
        if (!enabled || messageText.isBlank()) {
            return
        }
        view.setOnLongClickListener {
            ClipboardHelper.copyMessage(context, messageText)
            true
        }
    }

    private fun animateStreamingDots(tv: TextView) {
        val dotText = "•  •  •"
        val dotPositions = intArrayOf(0, 3, 6)
        var visibleDots = 1
        val runner = object : Runnable {
            override fun run() {
                if (tv.isAttachedToWindow && tv.text.toString().startsWith("•")) {
                    val frame = SpannableString(dotText)
                    dotPositions.forEachIndexed { index, position ->
                        frame.setSpan(
                            ForegroundColorSpan(if (index < visibleDots) tv.currentTextColor else Color.TRANSPARENT),
                            position,
                            position + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    tv.text = frame
                    visibleDots = (visibleDots % dotPositions.size) + 1
                    tv.postDelayed(this, 380L)
                }
            }
        }
        tv.post(runner)
    }

    private fun toolRow(item: ChatTimelineItem, tokens: ThemeTokens): View {
        val tool = item.toolEvent
        val expanded = tool?.isExpanded == true

        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(tokens.secondaryText)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            rotation = if (expanded) 90f else 0f
        }

        val titleText = TextView(context).apply {
            text = tool?.title ?: "Tool activity"
            Typography.applyFootnote(this, tokens)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val statusText = TextView(context).apply {
            text = tool?.status ?: "info"
            Typography.applyCaption(this, tokens, emphasis = true)
            background = Drawables.accentSoftSurface(context, tokens)
            setPadding(dp(DesignTokens.Spacing.sm), dp(2), dp(DesignTokens.Spacing.sm), dp(2))
            setTextColor(tokens.accent)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chevron, LinearLayout.LayoutParams(dp(18), dp(28)).apply {
                rightMargin = dp(DesignTokens.Spacing.sm)
            })
            addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(DesignTokens.Spacing.sm) })
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.glassSurface(context, tokens, DesignTokens.Radius.md)
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm)
            )
            setOnClickListener { tool?.eventId?.let(onToggleChatTool) }
            addView(headerRow)
            if (expanded) {
                val details = listOfNotNull(
                    tool?.summary?.let { "Summary\n$it" },
                    tool?.args?.let { "Args\n$it" },
                    tool?.output?.let { "Output\n${it.take(1200)}" },
                    tool?.error?.let { "Error\n$it" }
                ).joinToString("\n\n")

                val detailsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Drawables.glassInset(context, tokens, DesignTokens.Radius.sm)
                    setPadding(
                        dp(DesignTokens.Spacing.md),
                        dp(DesignTokens.Spacing.sm),
                        dp(DesignTokens.Spacing.md),
                        dp(DesignTokens.Spacing.sm)
                    )
                    addView(TextView(context).apply {
                        text = details.ifBlank { "No additional details." }
                        Typography.applyMono(this, tokens)
                        setLineSpacing(dp(2).toFloat(), 1.0f)
                    })
                }
                addView(detailsContainer, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(DesignTokens.Spacing.sm) })
            }
        }
    }

    private data class RevealCenter(val cx: Int, val cy: Int, val bubbleRadius: Float)

    private fun appearancePrefs(): AppearancePrefs = AppearancePrefsStore.load(context)

    private fun bubbleScreenCenter(): Pair<Int, Int>? {
        val params = bubbleParams ?: return null
        if (bubbleView == null) return null
        val size = if (params.width > 0) params.width else dp(DEFAULT_BUBBLE_SIZE_DP)
        return (params.x + size / 2) to (params.y + size / 2)
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
        val scroll = historyScrollView ?: return
        val child = scroll.getChildAt(0) ?: return
        val target = (child.height - scroll.height).coerceAtLeast(0)
        if (scroll.scrollY != target) {
            scroll.scrollTo(0, target)
        }
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
        detachOverlayView(panelView)
        detachOverlayView(panelScrimView)
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
        voiceSurface = null
        voiceStatusText = null
        voiceTranscriptText = null
        voiceTaskText = null
        voiceResultText = null
        voiceMuteButton = null
        voiceHangupButton = null
        historyContainer = null
        historyScrollView = null
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

    private fun buildVoiceSurface(tokens: ThemeTokens): LinearLayout {
        voiceStatusText = TextView(context).apply {
            Typography.applyHeadline(this, tokens, color = tokens.accent)
        }
        voiceTranscriptText = TextView(context).apply {
            Typography.applyBody(this, tokens, secondary = true)
            setPadding(0, dp(DesignTokens.Spacing.sm), 0, dp(DesignTokens.Spacing.sm))
            maxHeight = maxTranscriptHeight()
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        voiceTaskText = TextView(context).apply {
            Typography.applyCaption(this, tokens, emphasis = true)
            setPadding(0, 0, 0, dp(DesignTokens.Spacing.xs))
        }
        voiceResultText = TextView(context).apply {
            Typography.applyCaption(this, tokens, emphasis = false)
            setPadding(0, 0, 0, dp(DesignTokens.Spacing.sm))
        }
        voiceMuteButton = Button(context).apply {
            text = "Mute"
            isAllCaps = false
            textSize = DesignTokens.Text.callout
            setTextColor(tokens.primaryText)
            background = Drawables.pillSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setOnClickListener { onToggleVoiceMute() }
        }
        voiceHangupButton = Button(context).apply {
            text = "Hang up"
            isAllCaps = false
            textSize = DesignTokens.Text.callout
            setTextColor(tokens.accentInk)
            background = Drawables.dangerSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setOnClickListener {
                onStopVoice()
                voiceSurfaceForceHidden = true
                voiceSurface?.visibility = View.GONE
            }
        }
        val voiceActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(voiceMuteButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f).apply { rightMargin = dp(DesignTokens.Spacing.sm) })
            addView(voiceHangupButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md))
            background = Drawables.voiceTranscriptSurface(context, tokens)
            addView(voiceStatusText)
            addView(voiceTranscriptText)
            addView(voiceTaskText)
            addView(voiceResultText)
            addView(voiceActions)
            voiceSurface = this
        }
    }

    private fun showVoiceSurface() {
        if (panelView == null) {
            togglePanel(PanelPresentation.Popup)
        }
        voiceSurface?.visibility = View.VISIBLE
    }

    private fun renderVoiceState(state: VoiceRuntimeState) {
        applyBubbleVoiceIndicator(state)
        if (state.isActive) {
            voiceSurfaceForceHidden = false
        }
        val shouldShow = !voiceSurfaceForceHidden &&
            (state.isActive || state.status == VoiceRuntimeStatus.ERROR)
        voiceSurface?.visibility = if (shouldShow) View.VISIBLE else View.GONE
        voiceStatusText?.text = buildString {
            append("Voice: ")
            append(state.status.label)
            state.error?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
        }
        voiceTranscriptText?.text = state.transcript.ifBlank { "Voice transcript will appear here." }
        voiceTranscriptText?.post {
            voiceTranscriptText?.let { textView ->
                val scrollAmount = textView.layout?.let { layout ->
                    layout.getLineTop(textView.lineCount) - textView.height + textView.compoundPaddingBottom + textView.compoundPaddingTop
                } ?: 0
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount)
                }
            }
        }
        voiceTaskText?.text = buildString {
            val task = state.currentPhoneTask
            if (state.isPhoneTaskRunning && !task.isNullOrBlank()) {
                append("Task: ").append(task)
            } else if (state.queuedPhoneTasks > 0) {
                append("Tasks queued.")
            } else {
                append("No phone task running.")
            }
            if (state.queuedPhoneTasks > 0) {
                append(" Queued: ").append(state.queuedPhoneTasks)
            }
        }
        voiceResultText?.text = state.latestTaskResult ?: "Latest task result will appear here."
        voiceMuteButton?.text = if (state.isMuted) "Unmute" else "Mute"
        voiceMuteButton?.isEnabled = state.isActive
        voiceHangupButton?.isEnabled = state.status != VoiceRuntimeStatus.IDLE
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
            contentDescription = when {
                transcriptionState.isRecording -> "Cancel voice transcription"
                transcriptionState.isTranscribing -> "Transcribing audio"
                else -> "Start voice transcription"
            }
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
            contentDescription = when {
                transcriptionState.isRecording -> "Stop recording and transcribe"
                chatState.isRunning -> "Stop OpenClaw turn"
                else -> "Send message"
            }
            background = Drawables.accentSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setColorFilter(tokens.accentInk)
        }
    }

    private fun applyBubbleVoiceIndicator(state: VoiceRuntimeState) {
        val bubble = bubbleView ?: return
        val tokens = tokens()
        bubble.background = bubbleBackgroundForVoiceState(state, tokens)
        bubble.elevation = if (state.status == VoiceRuntimeStatus.IDLE) dp(DesignTokens.Elevation.mid).toFloat() else dp(DesignTokens.Elevation.popover + 6).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowColor = when (state.status) {
                VoiceRuntimeStatus.LISTENING,
                VoiceRuntimeStatus.THINKING,
                VoiceRuntimeStatus.SPEAKING -> tokens.success
                VoiceRuntimeStatus.CONNECTING,
                VoiceRuntimeStatus.ERROR -> tokens.danger
                VoiceRuntimeStatus.IDLE -> Color.TRANSPARENT
            }
            bubble.outlineAmbientShadowColor = shadowColor
            bubble.outlineSpotShadowColor = shadowColor
        }
        updateBubblePulse(isSpeaking = state.status == VoiceRuntimeStatus.SPEAKING)
    }

    private fun bubbleBackgroundForVoiceState(state: VoiceRuntimeState, tokens: ThemeTokens): GradientDrawable {
        return when (state.status) {
            VoiceRuntimeStatus.LISTENING,
            VoiceRuntimeStatus.THINKING,
            VoiceRuntimeStatus.SPEAKING -> Drawables.bubbleHalo(
                context,
                centerColor = DesignTokens.withAlpha(tokens.success, 0xE6),
                midColor = DesignTokens.withAlpha(tokens.success, 0x88)
            )
            VoiceRuntimeStatus.CONNECTING,
            VoiceRuntimeStatus.ERROR -> Drawables.bubbleHalo(
                context,
                centerColor = DesignTokens.withAlpha(tokens.danger, 0xE6),
                midColor = DesignTokens.withAlpha(tokens.danger, 0x88)
            )
            VoiceRuntimeStatus.IDLE -> Drawables.bubbleHalo(
                context,
                centerColor = Color.TRANSPARENT,
                midColor = Color.TRANSPARENT
            )
        }
    }

    private fun buildBubbleAvatarView(): View {
        bubblePetView = null
        val selection = AvatarConfigStore.load(context)
        if (selection is AvatarSelection.Pet) {
            val asset = AvatarLibrary.findCached(context, selection.id)
            val file = asset?.spritesheetFile
            if (file != null && file.exists()) {
                val view = PetAvatarView(context)
                if (view.loadFromFile(file)) {
                    bubblePetView = view
                    return view
                }
            }
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.openclaw_bubble_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md)
            )
        }
    }

    private fun updateBubbleAvatarForDrag(currentX: Int) {
        val pet = bubblePetView ?: return
        val previousX = bubbleLastDragX
        val now = System.currentTimeMillis()
        if (previousX == null) {
            bubbleLastDragX = currentX
            bubbleLastDragSampleMs = now
            return
        }
        val dx = currentX - previousX
        if (now - bubbleLastDragSampleMs >= DRAG_DIRECTION_SAMPLE_INTERVAL_MS || kotlin.math.abs(dx) >= DRAG_DIRECTION_PIXEL_THRESHOLD) {
            if (dx > DRAG_DIRECTION_PIXEL_THRESHOLD) {
                pet.setState(PetAnimation.State.RunningRight)
            } else if (dx < -DRAG_DIRECTION_PIXEL_THRESHOLD) {
                pet.setState(PetAnimation.State.RunningLeft)
            }
            bubbleLastDragX = currentX
            bubbleLastDragSampleMs = now
        }
    }

    private fun updateBubblePulse(isSpeaking: Boolean) {
        val bubble = bubbleView
        if (!isSpeaking || bubble == null) {
            stopBubblePulse()
            bubble?.scaleX = 1f
            bubble?.scaleY = 1f
            return
        }
        if (bubblePulseAnimator?.isStarted == true) {
            return
        }
        val scaleX = ObjectAnimator.ofFloat(bubble, View.SCALE_X, 1f, 1.08f).apply {
            duration = VOICE_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = trashShowInterpolator
        }
        val scaleY = ObjectAnimator.ofFloat(bubble, View.SCALE_Y, 1f, 1.08f).apply {
            duration = VOICE_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = trashShowInterpolator
        }
        bubblePulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun stopBubblePulse() {
        bubblePulseAnimator?.cancel()
        bubblePulseAnimator = null
    }

    private fun dismissConfirmation() {
        confirmationView?.let { windowManager.removeView(it) }
        confirmationScrimView?.let { windowManager.removeView(it) }
        confirmationView = null
        confirmationScrimView = null
    }

    private fun ensureTrashTarget() {
        if (trashTargetView != null) {
            return
        }
        val size = trashTargetSize()
        val target = ImageView(context).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Color.WHITE)
            background = trashTargetBackground(isActive = false)
            contentDescription = "Close Open Claw Agent bubble"
            elevation = dp(DesignTokens.Elevation.high).toFloat()
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.lg))
            alpha = 0f
            scaleX = TRASH_TARGET_HIDDEN_SCALE
            scaleY = TRASH_TARGET_HIDDEN_SCALE
            visibility = View.INVISIBLE
        }
        val params = overlayParams(width = size, height = size, focusable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DesignTokens.Spacing.xxl + 4)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.addView(target, params)
        trashTargetView = target
        updateTrashTargetBounds()
    }

    private fun showTrashTarget() {
        ensureTrashTarget()
        trashTargetView?.apply {
            animate().cancel()
            animate().setListener(null)
            background = trashTargetBackground(isActive = false)
            alpha = 0f
            scaleX = TRASH_TARGET_HIDDEN_SCALE
            scaleY = TRASH_TARGET_HIDDEN_SCALE
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRASH_TARGET_SHOW_MS)
                .setInterpolator(trashShowInterpolator)
                .start()
            post { updateTrashTargetBounds() }
        }
        isBubbleOverTrashTarget = false
    }

    private fun hideTrashTarget() {
        trashTargetView?.apply {
            animate().cancel()
            background = trashTargetBackground(isActive = false)
            animate()
                .alpha(0f)
                .scaleX(TRASH_TARGET_HIDDEN_SCALE)
                .scaleY(TRASH_TARGET_HIDDEN_SCALE)
                .setDuration(TRASH_TARGET_HIDE_MS)
                .setInterpolator(trashHideInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.INVISIBLE
                        animate().setListener(null)
                    }
                })
                .start()
        }
        isBubbleOverTrashTarget = false
    }

    private fun removeTrashTarget() {
        trashTargetView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(it)
        }
        trashTargetView = null
        trashTargetBounds = Rect()
        isBubbleOverTrashTarget = false
        isDismissAnimating = false
    }

    private fun updateTrashTargetState(params: WindowManager.LayoutParams, view: View): Boolean {
        updateTrashTargetBounds()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val centerX = location[0] + view.width / 2
        val centerY = location[1] + view.height / 2
        val dx = centerX - trashTargetBounds.centerX()
        val dy = centerY - trashTargetBounds.centerY()
        val radius = trashTargetBounds.width() / 2
        val isOverTarget = dx * dx + dy * dy <= radius * radius
        if (isBubbleOverTrashTarget != isOverTarget) {
            isBubbleOverTrashTarget = isOverTarget
            trashTargetView?.background = trashTargetBackground(isActive = isOverTarget)
        }
        return isOverTarget
    }

    private fun animateBubbleDismiss(bubble: View) {
        if (isDismissAnimating) {
            return
        }
        stopBubblePulse()
        val target = trashTargetView
        isDismissAnimating = true
        listOfNotNull(bubble, target).forEach { view ->
            view.animate().cancel()
            view.animate().setListener(null)
            view.visibility = View.VISIBLE
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        target?.background = trashTargetBackground(isActive = true)

        val animators = mutableListOf<Animator>()
        animators.add(dismissAnimatorFor(bubble))
        target?.let { animators.add(dismissAnimatorFor(it)) }

        AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismiss()
                }

                override fun onAnimationCancel(animation: Animator) {
                    isDismissAnimating = false
                }
            })
            start()
        }
    }

    private fun dismissAnimatorFor(view: View): AnimatorSet {
        return AnimatorSet().apply {
            playSequentially(
                scaleAnimator(view, 1.12f, TRASH_TARGET_PULSE_MS),
                scaleAnimator(view, 0.96f, TRASH_TARGET_PULSE_MS),
                shrinkAnimator(view)
            )
        }
    }

    private fun scaleAnimator(view: View, scale: Float, durationMs: Long): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
        ).apply {
            duration = durationMs
            interpolator = trashShowInterpolator
        }
    }

    private fun shrinkAnimator(view: View): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f)
        ).apply {
            duration = TRASH_TARGET_SHRINK_MS
            interpolator = trashHideInterpolator
        }
    }

    private fun updateTrashTargetBounds() {
        val target = trashTargetView ?: return
        val size = trashTargetSize()
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        if (location[0] == 0 && location[1] == 0) {
            val display = context.resources.displayMetrics
            val bottom = display.heightPixels - dp(28)
            val left = (display.widthPixels - size) / 2
            trashTargetBounds.set(left, bottom - size, left + size, bottom)
            return
        }
        val width = target.width.takeIf { it > 0 } ?: size
        val height = target.height.takeIf { it > 0 } ?: size
        trashTargetBounds.set(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun trashTargetBackground(isActive: Boolean): GradientDrawable {
        val tokens = tokens()
        val fill = if (isActive) tokens.danger else DesignTokens.withAlpha(if (tokens.isDark) 0xFF1F2A40.toInt() else 0xFF1F1F2C.toInt(), 0xE0)
        return Drawables.circle(fill = fill)
    }

    private fun trashTargetSize(): Int = dp(DesignTokens.Sizes.trash)

    private fun maxTranscriptHeight(): Int {
        val modalBudget = (context.resources.displayMetrics.heightPixels * VOICE_MODAL_MAX_SCREEN_FRACTION).toInt()
        return (modalBudget - dp(220)).coerceAtLeast(dp(96))
    }

    private inner class HostConnectionIndicatorButton(context: Context) : View(context) {
        private var tokens: ThemeTokens = tokens()
        private var state = lastHostConnectionState
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            isFocusable = true
            minimumWidth = dp(DesignTokens.Sizes.compact)
            minimumHeight = dp(DesignTokens.Sizes.compact)
        }

        fun bind(tokens: ThemeTokens, state: HostConnectionState) {
            this.tokens = tokens
            this.state = state
            background = Drawables.pillSurface(context, tokens)
            contentDescription = "${HostConnectionCopy.title(state.phase)}. Tap for host connection details."
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val color = hostConnectionColor(tokens, state.phase)
            val cx = width / 2f
            val cy = height / 2f
            haloPaint.color = DesignTokens.withAlpha(color, if (tokens.isDark) 0x44 else 0x2E)
            dotPaint.color = color
            canvas.drawCircle(cx, cy, dp(10).toFloat(), haloPaint)
            canvas.drawCircle(cx, cy, dp(7).toFloat(), dotPaint)
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
        }

        fun bind(tokens: ThemeTokens, ratio: Float?) {
            this.tokens = tokens
            this.ratio = ratio
            background = Drawables.pillSurface(context, tokens)
            contentDescription = ratio?.let { "Context window ${(it * 100).roundToInt()} percent used" } ?: "Context usage unknown"
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

    private fun rememberBubblePosition() {
        bubbleParams?.let {
            lastBubbleX = it.x
            lastBubbleY = it.y
        }
    }

    private fun suppressBubbleForFullscreen() {
        val bubble = bubbleView
        restoreBubbleAfterFullscreen = isOverlayAttached(bubble)
        rememberBubblePosition()
        stopBubblePulse()
        bubble?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(it)
        }
        bubbleView = null
        bubbleUnreadBadgeView = null
        bubbleParams = null
        removeTrashTarget()
    }

    private fun restoreBubbleAfterFullscreenDismiss() {
        val shouldRestore = restoreBubbleAfterFullscreen
        restoreBubbleAfterFullscreen = false
        if (shouldRestore && Settings.canDrawOverlays(context) && automationSuppressionDepth == 0 && bubbleView == null) {
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

    private fun detachOverlayView(view: View?) {
        view ?: return
        view.animate().cancel()
        view.animate().setListener(null)
        if (isOverlayAttached(view)) {
            runCatching { windowManager.removeViewImmediate(view) }
                .recoverCatching { windowManager.removeView(view) }
        }
    }

    private fun isOverlayAttached(view: View?): Boolean {
        return view?.isAttachedToWindow == true || view?.parent != null
    }

    companion object {
        private const val TRASH_TARGET_SHOW_MS = 140L
        private const val TRASH_TARGET_HIDE_MS = 110L
        private const val TRASH_TARGET_PULSE_MS = 55L
        private const val TRASH_TARGET_SHRINK_MS = 140L
        private const val TRASH_TARGET_HIDDEN_SCALE = 0.82f
        private const val DRAG_DIRECTION_SAMPLE_INTERVAL_MS = 80L
        private const val DRAG_DIRECTION_PIXEL_THRESHOLD = 3
        private const val VOICE_PULSE_MS = 720L
        private const val VOICE_MODAL_MAX_SCREEN_FRACTION = 0.40f
        private const val CHAT_MODAL_HEIGHT_FRACTION = 0.82f
        private const val MODAL_CLOSE_HOT_ZONE_WIDTH_DP = 56
        private const val MODAL_CLOSE_HOT_ZONE_HEIGHT_DP = 88
        private const val KEYBOARD_HEIGHT_ESTIMATE_FRACTION = 0.485f
        private const val KEYBOARD_COMPOSER_GAP_DP = 4
        private const val FULLSCREEN_KEYBOARD_BOTTOM_CLEARANCE_DP = 28
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

    private fun overlayParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
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
        val defaultY = defaultBounds.y
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
        setKeyboardSpacerHeight(keyboardBottomClearance())
        val minPanelHeight = dp(300)
        val desiredY = defaultY.coerceAtMost((keyboardTop - minPanelHeight).coerceAtLeast(dp(8)))
        val desiredHeight = (keyboardTop - desiredY - keyboardComposerGap()).coerceAtLeast(dp(240))
        if (params.height != desiredHeight || params.y != desiredY) {
            params.height = desiredHeight
            params.y = desiredY
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
        return when (presentation) {
            PanelPresentation.Popup -> {
                val height = popupPanelHeight(displayHeight)
                PanelBounds(height = height, y = displayHeight - height)
            }
            PanelPresentation.Fullscreen -> PanelBounds(height = fullscreenPanelHeight(displayHeight), y = 0)
        }
    }

    private fun popupPanelHeight(displayHeight: Int): Int {
        return (displayHeight * CHAT_MODAL_HEIGHT_FRACTION).toInt()
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
        val fullscreenExtraGap = if (activePanelPresentation == PanelPresentation.Fullscreen) {
            dp(DesignTokens.Spacing.sm)
        } else {
            0
        }
        return dp(KEYBOARD_COMPOSER_GAP_DP) + fullscreenExtraGap
    }

    private fun keyboardBottomClearance(): Int {
        return if (activePanelPresentation == PanelPresentation.Fullscreen) {
            dp(FULLSCREEN_KEYBOARD_BOTTOM_CLEARANCE_DP)
        } else {
            0
        }
    }

    private fun estimatedKeyboardHeight(displayHeight: Int): Int {
        return (displayHeight * KEYBOARD_HEIGHT_ESTIMATE_FRACTION).toInt()
            .coerceIn(dp(260), (displayHeight * 0.42f).toInt())
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
