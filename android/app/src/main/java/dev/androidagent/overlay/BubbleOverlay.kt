package dev.androidagent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.androidagent.AppearancePrefs
import dev.androidagent.AppearancePrefsStore
import dev.androidagent.R
import dev.androidagent.avatar.AvatarConfigStore
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.avatar.AvatarSelection
import dev.androidagent.avatar.PetAnimation
import dev.androidagent.avatar.PetAvatarView
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.latestUnreadSessionKey
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.hideFromAccessibility
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.VoiceRuntimeStatus

class BubbleOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTogglePanel: () -> Unit,
    private val onDismissPanelBeforeBubbleDismiss: () -> Unit,
    private val onDismiss: () -> Unit
) {
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
    private var bubbleHasUnread = false
    private var bubbleIsWorking = false
    private var bubbleIsDragging = false
    private var trashTargetView: ImageView? = null
    private var trashTargetBounds = Rect()
    private var isBubbleOverTrashTarget = false
    private var isDismissAnimating = false

    val isVisible: Boolean
        get() = isOverlayAttached(bubbleView)

    fun show(voiceState: VoiceRuntimeState, chatState: ChatState) {
        if (bubbleView != null) return

        val tokens = tokens()
        val badge = TextView(context).apply {
            id = R.id.openclaw_bubble_unread_badge
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
            id = R.id.openclaw_bubble
            background = bubbleBackgroundForVoiceState(voiceState, tokens)
            exposeToAccessibility(
                viewId = R.id.openclaw_bubble,
                description = "OpenAgent",
                focusable = true
            )
            elevation = dp(DesignTokens.Elevation.mid).toFloat()
            isClickable = true
            setOnClickListener { onTogglePanel() }
            addView(avatarView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(badge, unreadBadgeParams())
        }
        bubbleUnreadBadgeView = badge
        val initialBubbleDp = AppearancePrefsStore.load(context).bubbleSizeDp
            .coerceIn(AppearancePrefs.MIN_BUBBLE_SIZE_DP, AppearancePrefs.MAX_BUBBLE_SIZE_DP)
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
                updateTrashTargetState(dragView)
            },
            onDragEnd = { _, dragView ->
                bubbleIsDragging = false
                bubbleLastDragX = null
                applyBubbleRestingState()
                val shouldDismiss = updateTrashTargetState(dragView)
                if (shouldDismiss) {
                    onDismissPanelBeforeBubbleDismiss()
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
        ) { onTogglePanel() }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
        applyVoiceIndicator(voiceState)
        renderChatState(chatState)
    }

    fun hide() {
        rememberPosition()
        stopBubblePulse()
        detachOverlayView(windowManager, bubbleView)
        removeTrashTarget()
        clearBubbleReferences()
    }

    fun detachForAutomation(): Boolean {
        val shouldRestore = isVisible
        rememberPosition()
        stopBubblePulse()
        bubbleView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(windowManager, it)
        }
        removeTrashTarget()
        clearBubbleReferences()
        return shouldRestore
    }

    fun suppressForFullscreen(): Boolean {
        val shouldRestore = isVisible
        rememberPosition()
        stopBubblePulse()
        bubbleView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(windowManager, it)
        }
        removeTrashTarget()
        clearBubbleReferences()
        return shouldRestore
    }

    fun refreshAvatar(chatState: ChatState) {
        val bubble = bubbleView as? FrameLayout ?: return
        val badge = bubbleUnreadBadgeView
        bubble.removeAllViews()
        bubblePetView = null
        bubble.addView(buildBubbleAvatarView(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (badge != null) {
            bubble.addView(badge, unreadBadgeParams())
        }
        renderUnreadBadge(chatState)
        applyBubbleRestingState()
    }

    fun refreshSize(targetDp: Int) {
        val clamped = targetDp.coerceIn(AppearancePrefs.MIN_BUBBLE_SIZE_DP, AppearancePrefs.MAX_BUBBLE_SIZE_DP)
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val newSizePx = dp(clamped)
        if (params.width == newSizePx && params.height == newSizePx) return
        params.width = newSizePx
        params.height = newSizePx
        val display = context.resources.displayMetrics
        val horizontalInset = dp(8)
        val maxX = (display.widthPixels - newSizePx - horizontalInset).coerceAtLeast(horizontalInset)
        val maxY = (display.heightPixels - newSizePx - dp(8)).coerceAtLeast(dp(8))
        params.x = params.x.coerceIn(horizontalInset, maxX)
        params.y = params.y.coerceIn(dp(8), maxY)
        runCatching { windowManager.updateViewLayout(bubble, params) }
        rememberPosition()
    }

    fun renderChatState(state: ChatState) {
        bubbleIsWorking = state.isRunning
        renderUnreadBadge(state)
        applyBubbleRestingState()
    }

    fun renderUnreadBadge(state: ChatState) {
        val count = state.totalUnreadReplies
        bubbleHasUnread = count > 0
        applyBubbleRestingState()
        val badge = bubbleUnreadBadgeView ?: return
        if (count <= 0) {
            badge.visibility = View.GONE
            badge.text = ""
            bubbleView?.contentDescription = "OpenAgent"
            return
        }
        badge.text = badgeText(count)
        badge.visibility = View.VISIBLE
        val source = state.latestUnreadSessionKey()?.let { sessionKey ->
            state.unreadReplies[sessionKey]?.let { unread ->
                ChatPresentationHelpers.unreadReplySourceLabel(sessionKey, unread)
            }
        }
        bubbleView?.contentDescription = if (source.isNullOrBlank()) {
            "OpenAgent, $count unread replies"
        } else {
            "OpenAgent, $count unread ${if (count == 1) "reply" else "replies"} from $source"
        }
    }

    fun applyVoiceIndicator(state: VoiceRuntimeState) {
        val bubble = bubbleView ?: return
        val tokens = tokens()
        bubble.background = bubbleBackgroundForVoiceState(state, tokens)
        bubble.elevation = if (state.status == VoiceRuntimeStatus.IDLE) {
            dp(DesignTokens.Elevation.mid).toFloat()
        } else {
            dp(DesignTokens.Elevation.popover + 6).toFloat()
        }
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

    fun screenCenter(): Pair<Int, Int>? {
        val params = bubbleParams ?: return null
        if (bubbleView == null) return null
        val size = if (params.width > 0) params.width else dp(AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP)
        return (params.x + size / 2) to (params.y + size / 2)
    }

    fun rememberPosition() {
        bubbleParams?.let {
            lastBubbleX = it.x
            lastBubbleY = it.y
        }
    }

    fun removeTrashTarget() {
        trashTargetView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            detachOverlayView(windowManager, it)
        }
        trashTargetView = null
        trashTargetBounds = Rect()
        isBubbleOverTrashTarget = false
        isDismissAnimating = false
    }

    private fun unreadBadgeParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(20),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(2)
            rightMargin = dp(2)
        }
    }

    private fun clearBubbleReferences() {
        bubbleView = null
        bubbleUnreadBadgeView = null
        bubbleParams = null
        bubblePetView = null
        bubbleLastDragX = null
        bubbleIsDragging = false
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

    private fun badgeText(count: Int): String {
        return if (count > 99) "99+" else count.toString()
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
                    view.hideFromAccessibility()
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
            hideFromAccessibility()
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
        if (
            now - bubbleLastDragSampleMs >= DRAG_DIRECTION_SAMPLE_INTERVAL_MS ||
            kotlin.math.abs(dx) >= DRAG_DIRECTION_PIXEL_THRESHOLD
        ) {
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

    private fun ensureTrashTarget() {
        if (trashTargetView != null) {
            return
        }
        val size = trashTargetSize()
        val target = ImageView(context).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Color.WHITE)
            background = trashTargetBackground(isActive = false)
            exposeToAccessibility(
                viewId = R.id.openclaw_bubble_trash_target,
                description = "Close OpenAgent bubble"
            )
            elevation = dp(DesignTokens.Elevation.high).toFloat()
            setPadding(
                dp(DesignTokens.Spacing.lg),
                dp(DesignTokens.Spacing.lg),
                dp(DesignTokens.Spacing.lg),
                dp(DesignTokens.Spacing.lg)
            )
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

    private fun updateTrashTargetState(view: View): Boolean {
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
        val fill = if (isActive) {
            tokens.danger
        } else {
            DesignTokens.withAlpha(if (tokens.isDark) 0xFF1F2A40.toInt() else 0xFF1F1F2C.toInt(), 0xE0)
        }
        return Drawables.circle(fill = fill)
    }

    private fun trashTargetSize(): Int = dp(DesignTokens.Sizes.trash)

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
                        keepInsideScreen(view, params)
                        windowManager.updateViewLayout(view, params)
                        onDrag(params, view)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val isClick = !moved && event.eventTime - downTime < 250
                    if (isClick) {
                        onClick()
                    } else {
                        onDragEnd(params, view)
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

    private fun keepInsideScreen(view: View, params: WindowManager.LayoutParams) {
        val display = context.resources.displayMetrics
        val horizontalInset = if (view.width >= display.widthPixels - dp(4)) 0 else dp(8)
        val maxX = (display.widthPixels - view.width - horizontalInset).coerceAtLeast(horizontalInset)
        val maxY = (display.heightPixels - view.height - dp(8)).coerceAtLeast(dp(8))
        params.x = params.x.coerceIn(horizontalInset, maxX)
        params.y = params.y.coerceIn(dp(8), maxY)
    }

    private fun tokens(): ThemeTokens = DesignTokens.resolve(context)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TRASH_TARGET_SHOW_MS = 140L
        const val TRASH_TARGET_HIDE_MS = 110L
        const val TRASH_TARGET_PULSE_MS = 55L
        const val TRASH_TARGET_SHRINK_MS = 140L
        const val TRASH_TARGET_HIDDEN_SCALE = 0.82f
        const val DRAG_DIRECTION_SAMPLE_INTERVAL_MS = 80L
        const val DRAG_DIRECTION_PIXEL_THRESHOLD = 3
        const val VOICE_PULSE_MS = 720L
    }
}
