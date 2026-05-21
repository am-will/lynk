package dev.androidagent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens.dp

/**
 * Status bar above the composer that:
 *  - Pulses three dots while [active] is true (mirrors the chat "thinking" pattern).
 *  - Animates a soft accent-tinted shimmer sweep across the status text while active.
 *  - Falls back to a static, low-key caption when [active] is false.
 *
 * The actual status copy is set via [setText]. The view stays attached even when
 * idle so layout doesn't jump.
 */
class StatusUpdateView(context: Context, private var tokens: ThemeTokens) : LinearLayout(context) {

    private val dots = ThinkingDotsView(context, tokens)
    private val label = ShimmerTextView(context, tokens)
    private var active: Boolean = false

    init {
        exposeToAccessibility(
            viewId = R.id.openclaw_status_line,
            description = "OpenClaw status",
            liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        )
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(context, DesignTokens.Spacing.lg)
        val padV = dp(context, DesignTokens.Spacing.xs)
        setPadding(padH, padV, padH, padV)

        addView(
            dots,
            LayoutParams(dp(context, 22), dp(context, 12)).apply {
                rightMargin = dp(context, DesignTokens.Spacing.sm)
            }
        )
        addView(
            label,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        Typography.applyCaption(label, tokens, emphasis = false)
        label.exposeToAccessibility(
            viewId = R.id.openclaw_status_line_label,
            description = "OpenClaw status text",
            liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        )
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        dots.visibility = View.GONE
        dots.hideFromAccessibility()
    }

    fun applyTokens(newTokens: ThemeTokens) {
        tokens = newTokens
        dots.applyTokens(newTokens)
        label.applyTokens(newTokens)
        Typography.applyCaption(label, newTokens, emphasis = false)
    }

    fun setText(text: CharSequence?) {
        label.text = text ?: ""
        updateAccessibilityState(
            description = "OpenClaw status",
            stateDescription = label.text
        )
    }

    val textValue: CharSequence
        get() = label.text

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            dots.visibility = View.VISIBLE
            dots.start()
            label.startShimmer()
        } else {
            dots.stop()
            dots.visibility = View.GONE
            label.stopShimmer()
        }
    }

    override fun onDetachedFromWindow() {
        dots.stop()
        label.stopShimmer()
        super.onDetachedFromWindow()
    }
}

private class ThinkingDotsView(context: Context, private var tokens: ThemeTokens) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var phase: Float = 0f

    init {
        paint.color = tokens.accent
    }

    fun applyTokens(newTokens: ThemeTokens) {
        tokens = newTokens
        paint.color = newTokens.accent
        invalidate()
    }

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val radius = h * 0.22f
        val spacing = (w - radius * 6f) / 4f
        val cy = h / 2f
        for (i in 0 until 3) {
            val cx = spacing + radius + i * (radius * 2f + spacing)
            val offset = (i * 0.18f)
            val t = ((phase + offset) % 1f)
            val scale = 0.55f + 0.45f * kotlin.math.sin(t * Math.PI * 2.0).toFloat().let { if (it < 0f) 0f else it }
            paint.alpha = (140 + 115 * scale).toInt().coerceIn(80, 255)
            canvas.drawCircle(cx, cy, radius * (0.7f + 0.5f * scale), paint)
        }
    }
}

private class ShimmerTextView(context: Context, private var tokens: ThemeTokens) : TextView(context) {
    private var animator: ValueAnimator? = null
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var translate: Float = 0f

    fun applyTokens(newTokens: ThemeTokens) {
        tokens = newTokens
        if (animator?.isRunning == true) buildShader()
        invalidate()
    }

    fun startShimmer() {
        buildShader()
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                translate = it.animatedValue as Float
                paint.shader?.let { sh ->
                    matrix.reset()
                    val span = (width * 2f) + dp(context, 60).toFloat()
                    matrix.setTranslate(-span / 2f + translate * span, 0f)
                    sh.setLocalMatrix(matrix)
                }
                invalidate()
            }
            start()
        }
    }

    fun stopShimmer() {
        animator?.cancel()
        animator = null
        paint.shader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (animator?.isRunning == true) buildShader()
    }

    private fun buildShader() {
        if (width <= 0) return
        val base = currentTextColor
        val highlight = tokens.accent
        val baseAlpha = (base ushr 24) and 0xFF
        val highlightArgb = (baseAlpha shl 24) or (highlight and 0x00FFFFFF)
        val span = (width * 2f) + dp(context, 60).toFloat()
        shader = LinearGradient(
            0f, 0f, span, 0f,
            intArrayOf(base, highlightArgb, base),
            floatArrayOf(0.3f, 0.5f, 0.7f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }
}
