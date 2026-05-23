package dev.androidagent.settings

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography

object SettingsForms {
    fun checkRow(
        context: Context,
        tokens: ThemeTokens,
        titleText: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = SettingsComponents.dp(context, 36)
        }
        val state = booleanArrayOf(checked)
        val icon = ImageView(context).apply {
            setImageResource(if (state[0]) R.drawable.ic_check_box else R.drawable.ic_check_box_outline)
            setColorFilter(if (state[0]) tokens.accent else tokens.secondaryText)
            layoutParams = LinearLayout.LayoutParams(SettingsComponents.dp(context, 22), SettingsComponents.dp(context, 22))
        }
        row.addView(icon)
        row.addView(TextView(context).apply {
            text = titleText
            Typography.applyCallout(this, tokens)
            setPadding(SettingsComponents.dp(context, DesignTokens.Spacing.sm + 2), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            state[0] = !state[0]
            icon.setImageResource(if (state[0]) R.drawable.ic_check_box else R.drawable.ic_check_box_outline)
            icon.setColorFilter(if (state[0]) tokens.accent else tokens.secondaryText)
            onChange(state[0])
        }
        return row
    }

    fun infoBanner(context: Context, tokens: ThemeTokens, title: String, subtitle: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Drawables.rounded(
                fill = ColorUtils.with(tokens.accent, 0x1A),
                radius = SettingsComponents.dp(context, SettingsComponents.CONTAINER_RADIUS).toFloat(),
                strokeColor = ColorUtils.with(tokens.accent, 0x55),
                strokeWidth = SettingsComponents.dp(context, 1).coerceAtLeast(1)
            )
            setPadding(
                SettingsComponents.dp(context, DesignTokens.Spacing.md),
                SettingsComponents.dp(context, DesignTokens.Spacing.md - 2),
                SettingsComponents.dp(context, DesignTokens.Spacing.md),
                SettingsComponents.dp(context, DesignTokens.Spacing.md - 2)
            )
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(tokens.accent)
            layoutParams = LinearLayout.LayoutParams(SettingsComponents.dp(context, 18), SettingsComponents.dp(context, 18))
        })
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SettingsComponents.dp(context, DesignTokens.Spacing.sm + 2), 0, 0, 0)
        }
        copy.addView(TextView(context).apply {
            text = title
            Typography.applyFootnote(this, tokens)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        copy.addView(TextView(context).apply {
            text = subtitle
            Typography.applyFootnote(this, tokens, secondary = true)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun filePathRow(context: Context, tokens: ThemeTokens, path: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SettingsComponents.insetBackground(context, tokens)
            setPadding(
                SettingsComponents.dp(context, DesignTokens.Spacing.md),
                SettingsComponents.dp(context, DesignTokens.Spacing.sm + 2),
                SettingsComponents.dp(context, DesignTokens.Spacing.md),
                SettingsComponents.dp(context, DesignTokens.Spacing.sm + 2)
            )
            minimumHeight = SettingsComponents.dp(context, 48)
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_file)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(SettingsComponents.dp(context, 18), SettingsComponents.dp(context, 18))
        })
        row.addView(TextView(context).apply {
            text = if (path.isBlank()) "(no path set)" else path
            Typography.applyFootnote(this, tokens, secondary = path.isBlank())
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(SettingsComponents.dp(context, DesignTokens.Spacing.sm), 0, SettingsComponents.dp(context, DesignTokens.Spacing.sm), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(SettingsComponents.dp(context, 16), SettingsComponents.dp(context, 16))
        })
        return row
    }
}
