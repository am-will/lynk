package dev.androidagent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens

class HostConnectionIndicatorButton(context: Context) : View(context) {
    private var tokens: ThemeTokens = DesignTokens.resolve(context)
    private var state = HostConnectionState(
        phase = HostConnectionPhase.CONNECTING,
        message = "Checking host connection..."
    )
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
