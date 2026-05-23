package dev.androidagent.overlay

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isNotEmpty
import dev.androidagent.R
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatTimelineItem
import dev.androidagent.chat.ChatTimelineKind
import dev.androidagent.chat.ChatTimelineRenderer
import dev.androidagent.localmodel.LocalResponseTextNormalizer
import dev.androidagent.ui.ClipboardHelper
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.MarkdownFencedCodeChunk
import dev.androidagent.ui.MarkdownFencedCodeParser
import dev.androidagent.ui.MarkdownRenderer
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.hideFromAccessibility

class ChatTimelineBinder(
    private val context: Context,
    private val onToggleChatTool: (String) -> Unit
) {
    private var historyContainer: LinearLayout? = null
    private var historyScrollView: ScrollView? = null

    fun bind(container: LinearLayout, scrollView: ScrollView) {
        historyContainer = container
        historyScrollView = scrollView
    }

    fun clear() {
        historyContainer = null
        historyScrollView = null
    }

    fun render(state: ChatState, showToolCalls: Boolean, brand: ClientBrandPresentation) {
        val container = historyContainer ?: return
        val tokens = tokens()
        container.removeAllViews()
        val plan = ChatTimelineRenderer.plan(state, showToolCalls)
        if (plan.isEmpty) {
            container.addView(emptyHistoryView(tokens, brand))
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

    fun snapToBottom() {
        val scroll = historyScrollView ?: return
        val child = scroll.getChildAt(0) ?: return
        val target = (child.height - scroll.height).coerceAtLeast(0)
        if (scroll.scrollY != target) {
            scroll.scrollTo(0, target)
        }
    }

    private fun reasoningBlock(item: ChatTimelineItem, tokens: ThemeTokens): View {
        return LinearLayout(context).apply {
            exposeToAccessibility(
                description = "Reasoning stream",
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
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

    private fun emptyHistoryView(tokens: ThemeTokens, brand: ClientBrandPresentation): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(DesignTokens.Spacing.xxl), dp(48), dp(DesignTokens.Spacing.xxl), dp(48))

            addView(ImageView(context).apply {
                setImageResource(brand.logoRes)
                alpha = 0.65f
                hideFromAccessibility()
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
                text = brand.copy.emptyHistoryText
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
        if (role == "system") {
            val maxWidth = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                addView(systemMessageBubble(item, tokens, maxWidth), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                })
            }
        }

        val isUser = role == "user"
        val isAssistant = role == "assistant"
        val isStreaming = !isUser && item.isStreaming && item.text.isBlank()

        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val bubble = if (isAssistant && !isStreaming) {
            val messageText = LocalResponseTextNormalizer.normalize(item.text)
            val chunks = MarkdownFencedCodeParser.parse(messageText)
            if (chunks.any { it is MarkdownFencedCodeChunk.CodeBlock }) {
                assistantMessageBubbleWithCodeBlocks(
                    messageText = messageText,
                    chunks = chunks,
                    tokens = tokens,
                    maxWidth = maxWidth
                )
            } else {
                plainMessageBubble(
                    messageText = messageText,
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
            exposeToAccessibility(
                description = if (isUser) "User message" else "Assistant message",
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
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

    private fun systemMessageBubble(
        item: ChatTimelineItem,
        tokens: ThemeTokens,
        maxWidth: Int
    ): TextView {
        return TextView(context).apply {
            Typography.applyCallout(this, tokens)
            setTextColor(tokens.bubbleAssistantInk)
            setPadding(
                dp(DesignTokens.Spacing.md + 2),
                dp(DesignTokens.Spacing.sm + 2),
                dp(DesignTokens.Spacing.md + 2),
                dp(DesignTokens.Spacing.sm + 2)
            )
            setLineSpacing(dp(DesignTokens.Spacing.sm).toFloat(), 1.0f)
            background = Drawables.chatBubbleAssistant(context, tokens)
            gravity = Gravity.START
            this.maxWidth = maxWidth
            text = item.text.ifBlank { "Status" }
            attachMessageCopyGesture(this, item.text)
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
            background = if (isUser) {
                Drawables.chatBubbleUser(context, tokens)
            } else {
                Drawables.chatBubbleAssistant(context, tokens)
            }
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
            exposeToAccessibility(
                description = "Assistant message",
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
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
            contentDescription = "Code block: ${chunk.copyText.take(160)}"
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
        var activeDot = 0
        val runner = object : Runnable {
            override fun run() {
                if (tv.isAttachedToWindow && tv.text.toString().startsWith("•")) {
                    val frame = SpannableString(dotText)
                    dotPositions.forEachIndexed { index, position ->
                        frame.setSpan(
                            ForegroundColorSpan(if (index == activeDot) tv.currentTextColor else Color.TRANSPARENT),
                            position,
                            position + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    tv.text = frame
                    activeDot = (activeDot + 1) % dotPositions.size
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
            hideFromAccessibility()
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
            exposeToAccessibility(
                description = buildString {
                    append(tool?.title ?: "Tool activity")
                    append(", ")
                    append(tool?.status ?: "info")
                    append(if (expanded) ", expanded" else ", collapsed")
                },
                stateDescription = if (expanded) "expanded" else "collapsed",
                focusable = true,
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
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
                if (details.isNotBlank()) {
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
                            text = details
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
    }

    private fun tokens(): ThemeTokens = DesignTokens.resolve(context)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
