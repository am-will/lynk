package dev.androidagent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.Typography
import kotlinx.coroutines.CompletableDeferred

class ConfirmationOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var confirmationView: View? = null
    private var confirmationScrimView: View? = null

    fun ask(message: String, preview: String?): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        if (!Settings.canDrawOverlays(context)) {
            deferred.complete(false)
            return deferred
        }
        dismiss()
        val tokens = DesignTokens.resolve(context)

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
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.xs),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.xs)
            )
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
                dismiss()
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
                dismiss()
                deferred.complete(true)
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f).apply {
                rightMargin = dp(DesignTokens.Spacing.sm)
            })
            addView(allowButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f))
        }
        card.addView(buttons)

        val scrim = View(context).apply {
            setBackgroundColor(tokens.scrim)
            setOnClickListener {
                dismiss()
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
        val params = overlayParams(
            width = cardWidth,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        windowManager.addView(card, params)
        confirmationView = card
        return deferred
    }

    fun dismiss() {
        detachOverlayView(windowManager, confirmationView)
        detachOverlayView(windowManager, confirmationScrimView)
        confirmationView = null
        confirmationScrimView = null
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
