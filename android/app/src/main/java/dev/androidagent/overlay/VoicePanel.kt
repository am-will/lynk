package dev.androidagent.overlay

import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.VoiceRuntimeStatus

class VoicePanel(
    private val context: Context,
    private val onToggleVoiceMute: () -> Unit,
    private val onStopVoice: () -> Unit
) {
    private var surface: LinearLayout? = null
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var taskText: TextView? = null
    private var resultText: TextView? = null
    private var muteButton: Button? = null
    private var hangupButton: Button? = null
    private var forceHidden = false

    fun build(tokens: ThemeTokens): LinearLayout {
        statusText = TextView(context).apply {
            Typography.applyHeadline(this, tokens, color = tokens.accent)
        }
        transcriptText = TextView(context).apply {
            Typography.applyBody(this, tokens, secondary = true)
            setPadding(0, dp(DesignTokens.Spacing.sm), 0, dp(DesignTokens.Spacing.sm))
            maxHeight = maxTranscriptHeight()
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        taskText = TextView(context).apply {
            Typography.applyCaption(this, tokens, emphasis = true)
            setPadding(0, 0, 0, dp(DesignTokens.Spacing.xs))
        }
        resultText = TextView(context).apply {
            Typography.applyCaption(this, tokens, emphasis = false)
            setPadding(0, 0, 0, dp(DesignTokens.Spacing.sm))
        }
        muteButton = Button(context).apply {
            text = "Mute"
            isAllCaps = false
            textSize = DesignTokens.Text.callout
            setTextColor(tokens.primaryText)
            background = Drawables.pillSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setOnClickListener { onToggleVoiceMute() }
        }
        hangupButton = Button(context).apply {
            text = "Hang up"
            isAllCaps = false
            textSize = DesignTokens.Text.callout
            setTextColor(tokens.accentInk)
            background = Drawables.dangerSurface(context, tokens, DesignTokens.Radius.pill)
            backgroundTintList = null
            setOnClickListener {
                onStopVoice()
                forceHidden = true
                surface?.visibility = View.GONE
            }
        }
        val voiceActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(muteButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f).apply {
                rightMargin = dp(DesignTokens.Spacing.sm)
            })
            addView(hangupButton, LinearLayout.LayoutParams(0, dp(DesignTokens.Sizes.action), 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                dp(DesignTokens.Spacing.lg),
                dp(DesignTokens.Spacing.md),
                dp(DesignTokens.Spacing.lg),
                dp(DesignTokens.Spacing.md)
            )
            background = Drawables.voiceTranscriptSurface(context, tokens)
            addView(statusText)
            addView(transcriptText)
            addView(taskText)
            addView(resultText)
            addView(voiceActions)
            surface = this
        }
    }

    fun show() {
        surface?.visibility = View.VISIBLE
    }

    fun render(state: VoiceRuntimeState) {
        if (state.isActive) {
            forceHidden = false
        }
        val shouldShow = !forceHidden && (state.isActive || state.status == VoiceRuntimeStatus.ERROR)
        surface?.visibility = if (shouldShow) View.VISIBLE else View.GONE
        statusText?.text = buildString {
            append("Voice: ")
            append(state.status.label)
            state.error?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
        }
        transcriptText?.text = state.transcript.ifBlank { "Voice transcript will appear here." }
        transcriptText?.post {
            transcriptText?.let { textView ->
                val scrollAmount = textView.layout?.let { layout ->
                    layout.getLineTop(textView.lineCount) - textView.height + textView.compoundPaddingBottom + textView.compoundPaddingTop
                } ?: 0
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount)
                }
            }
        }
        taskText?.text = buildString {
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
        resultText?.text = state.latestTaskResult ?: "Latest task result will appear here."
        muteButton?.text = if (state.isMuted) "Unmute" else "Mute"
        muteButton?.isEnabled = state.isActive
        hangupButton?.isEnabled = state.status != VoiceRuntimeStatus.IDLE
    }

    fun clear() {
        surface = null
        statusText = null
        transcriptText = null
        taskText = null
        resultText = null
        muteButton = null
        hangupButton = null
    }

    private fun maxTranscriptHeight(): Int {
        val modalBudget = (context.resources.displayMetrics.heightPixels * VOICE_MODAL_MAX_SCREEN_FRACTION).toInt()
        return (modalBudget - dp(220)).coerceAtLeast(dp(96))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val VOICE_MODAL_MAX_SCREEN_FRACTION = 0.40f
    }
}
