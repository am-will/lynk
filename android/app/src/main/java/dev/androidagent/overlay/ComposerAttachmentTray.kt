package dev.androidagent.overlay

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

class ComposerAttachmentTray(
    private val context: Context,
    private val onChanged: () -> Unit
) {
    private val pendingAttachments = mutableListOf<StoredChatAttachment>()
    private var container: LinearLayout? = null
    private var lastTokens: ThemeTokens? = null

    fun build(tokens: ThemeTokens): LinearLayout {
        lastTokens = tokens
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            container = this
            render(tokens)
        }
    }

    fun add(attachment: StoredChatAttachment) {
        pendingAttachments.removeAll { it.id == attachment.id }
        pendingAttachments.add(attachment)
        render()
        onChanged()
    }

    fun clear() {
        if (pendingAttachments.isEmpty()) return
        pendingAttachments.clear()
        render()
        onChanged()
    }

    fun clearSurface() {
        container = null
    }

    fun snapshot(): List<StoredChatAttachment> = pendingAttachments.toList()

    fun hasContent(): Boolean = pendingAttachments.isNotEmpty()

    fun render(tokens: ThemeTokens? = null) {
        val surface = container ?: return
        tokens?.let { lastTokens = it }
        val resolvedTokens = tokens ?: lastTokens ?: return
        surface.removeAllViews()
        surface.visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        if (pendingAttachments.isEmpty()) {
            return
        }
        surface.setPadding(
            dp(DesignTokens.Spacing.sm),
            0,
            dp(DesignTokens.Spacing.sm),
            dp(DesignTokens.Spacing.xs)
        )
        pendingAttachments.forEach { attachment ->
            surface.addView(attachmentChip(attachment, resolvedTokens), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(DesignTokens.Spacing.xs)
            })
        }
    }

    private fun attachmentChip(attachment: StoredChatAttachment, tokens: ThemeTokens): TextView =
        TextView(context).apply {
            text = "${attachmentChipPrefix(attachment)} ${attachment.displayName}  x"
            textSize = DesignTokens.Text.caption
            maxWidth = dp(190)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(tokens.primaryText)
            background = Drawables.pillSurface(context, tokens)
            backgroundTintList = null
            setPadding(
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.xs),
                dp(DesignTokens.Spacing.sm),
                dp(DesignTokens.Spacing.xs)
            )
            exposeToAccessibility(
                description = "Remove attachment ${attachment.displayName}",
                focusable = true
            )
            setOnClickListener {
                pendingAttachments.removeAll { it.id == attachment.id }
                render(tokens)
                onChanged()
            }
        }

    private fun attachmentChipPrefix(attachment: StoredChatAttachment): String =
        if (attachment.isImage) "Image" else "File"

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
