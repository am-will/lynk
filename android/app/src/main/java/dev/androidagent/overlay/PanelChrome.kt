package dev.androidagent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.StatusUpdateView
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.hideFromAccessibility

data class PanelChromeHandle(
    val host: FrameLayout,
    val content: LinearLayout,
    val scrim: View,
    val panelParams: WindowManager.LayoutParams,
    val scrimParams: WindowManager.LayoutParams,
    val historyContainer: LinearLayout,
    val historyScrollView: ScrollView,
    val keyboardSpacer: View,
    val defaultHeight: Int
)

class PanelChrome(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onBackPressed()
        fun onScrimClicked()
        fun onWindowFocusChanged(hasWindowFocus: Boolean)
        fun isPickerShowing(): Boolean
    }

    fun build(
        tokens: ThemeTokens,
        presentation: PanelPresentation,
        header: View,
        voice: View,
        status: StatusUpdateView,
        composer: View,
        defaultBounds: PanelBounds,
        dismissOnBack: Boolean = true
    ): PanelChromeHandle {
        val history = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.md)
            )
            clipToPadding = false
        }
        val historyScroll = ScrollView(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_chat_history,
                description = "Chat history",
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
            addView(history)
        }
        val keyboardSpacer = View(context).apply {
            visibility = View.GONE
            hideFromAccessibility()
        }
        val chrome = buildContent(tokens, header, voice, historyScroll, status, composer, keyboardSpacer, dismissOnBack)
        val host = if (dismissOnBack) {
            PanelHost(context)
        } else {
            FrameLayout(context)
        }.apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_panel_host,
                description = "Chat panel",
                focusable = true
            )
            isFocusableInTouchMode = true
            addView(chrome, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        val display = context.resources.displayMetrics
        val params = overlayParams(
            width = display.widthPixels,
            height = defaultBounds.height,
            focusable = true
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = defaultBounds.y
        }
        val scrim = View(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_panel_scrim,
                description = "Dismiss chat panel"
            )
            setBackgroundColor(tokens.scrim)
            setOnClickListener { callbacks.onScrimClicked() }
        }
        val scrimParams = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return PanelChromeHandle(
            host = host,
            content = chrome,
            scrim = scrim,
            panelParams = params,
            scrimParams = scrimParams,
            historyContainer = history,
            historyScrollView = historyScroll,
            keyboardSpacer = keyboardSpacer,
            defaultHeight = defaultBounds.height
        )
    }

    private fun buildContent(
        tokens: ThemeTokens,
        header: View,
        voice: View,
        historyScroll: ScrollView,
        status: StatusUpdateView,
        composer: View,
        keyboardSpacer: View,
        dismissOnBack: Boolean
    ): LinearLayout {
        return LinearLayout(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_panel_content,
                description = "Chat controls",
                focusable = true
            )
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            background = Drawables.glassPanel(context, tokens)
            elevation = dp(DesignTokens.Elevation.popover).toFloat()
            setPadding(0, 0, 0, 0)
            if (dismissOnBack) {
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        callbacks.onBackPressed()
                        true
                    } else {
                        false
                    }
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
            addView(status, LinearLayout.LayoutParams(
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
    }

    private inner class PanelHost(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (isModalCloseHotZone(event)) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    callbacks.onBackPressed()
                }
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                callbacks.onBackPressed()
                return true
            }
            return super.dispatchKeyEventPreIme(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            callbacks.onWindowFocusChanged(hasWindowFocus)
        }

        private fun isModalCloseHotZone(event: MotionEvent): Boolean {
            if (callbacks.isPickerShowing()) {
                return false
            }
            if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_UP) {
                return false
            }
            val closeZoneWidth = dp(MODAL_CLOSE_HOT_ZONE_WIDTH_DP)
            val closeZoneHeight = dp(MODAL_CLOSE_HOT_ZONE_HEIGHT_DP)
            return event.x >= width - closeZoneWidth && event.y <= closeZoneHeight
        }
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

    private companion object {
        const val MODAL_CLOSE_HOT_ZONE_WIDTH_DP = 56
        const val MODAL_CLOSE_HOT_ZONE_HEIGHT_DP = 88
    }
}
