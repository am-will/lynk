package dev.androidagent.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.drawable.RippleDrawable
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import dev.androidagent.ui.exposeToAccessibility

/**
 * Re-usable, mockup-aligned settings components.
 *
 * The goal is a dark, card-driven, iconographic UI matching the inspiration:
 * - Compact dark cards with hairline borders (no light "glass" surfaces in dark theme).
 * - Colored icon badges next to category rows.
 * - Status chips with icon + label + value + colored dot.
 * - Consistent typography hierarchy via [Typography].
 */
object SettingsComponents {

    fun dp(context: Context, value: Int): Int = DesignTokens.dp(context, value)
    fun tokens(context: Context): ThemeTokens = DesignTokens.resolve(context)

    // -------- core surfaces --------

    fun screen(context: Context): LinearLayout {
        val t = tokens(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
        }
    }

    fun card(context: Context, tokens: ThemeTokens, padding: Int = DesignTokens.Spacing.lg): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = darkCardBackground(context, tokens)
            setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
        }
    }

    fun darkCardBackground(context: Context, tokens: ThemeTokens): GradientDrawable {
        return Drawables.rounded(
            fill = tokens.surface,
            radius = dp(context, DesignTokens.Radius.lg).toFloat(),
            strokeColor = tokens.border,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    fun insetBackground(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.md): GradientDrawable {
        return Drawables.rounded(
            fill = tokens.surfaceInset,
            radius = dp(context, radius).toFloat(),
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    fun pillBackground(context: Context, tokens: ThemeTokens, fill: Int, stroke: Int? = null): GradientDrawable {
        return Drawables.rounded(
            fill = fill,
            radius = dp(context, DesignTokens.Radius.pill).toFloat(),
            strokeColor = stroke,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    fun verticalMargin(context: Context, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, top)
            bottomMargin = dp(context, bottom)
        }
    }

    data class HubLayoutMetrics(
        val horizontalPaddingDp: Int,
        val verticalPaddingDp: Int,
        val statusGridHeightDp: Int,
        val categoryIconSizeDp: Int,
        val headerAvatarSizeDp: Int,
        val statusChipIconSizeDp: Int
    )

    /** Scale hub spacing from the shell content area so the grid + five rows fill without scrolling. */
    fun hubLayoutMetrics(context: Context): HubLayoutMetrics {
        val dm = context.resources.displayMetrics
        val heightDp = (dm.heightPixels / dm.density).toInt()
        val widthDp = (dm.widthPixels / dm.density).toInt()
        val compact = heightDp < 700 || widthDp <= 400
        val bottomNavReserveDp = 68
        val contentHeightDp = (heightDp - bottomNavReserveDp).coerceAtLeast(520)

        val horizontalPadding = if (compact) DesignTokens.Spacing.lg else DesignTokens.Spacing.xl
        val verticalPadding = if (compact) DesignTokens.Spacing.md else DesignTokens.Spacing.lg
        val headerReserve = if (compact) 88 else 108
        val sectionGaps = DesignTokens.Spacing.lg * 2
        val categoryGaps = (DesignTokens.Spacing.sm + 2) * 4
        val categoryReserve = (contentHeightDp - verticalPadding * 2 - headerReserve - sectionGaps - categoryGaps)
            .coerceAtLeast(240)
        val statusGridHeight = (categoryReserve * 0.24f).toInt().coerceIn(
            if (compact) 108 else 132,
            if (compact) 124 else 156
        )

        return HubLayoutMetrics(
            horizontalPaddingDp = horizontalPadding,
            verticalPaddingDp = verticalPadding,
            statusGridHeightDp = statusGridHeight,
            categoryIconSizeDp = if (compact) 38 else 44,
            headerAvatarSizeDp = if (compact) 52 else 64,
            statusChipIconSizeDp = if (compact) 15 else 16
        )
    }

    // -------- icon badges --------

    enum class BadgeTone { Teal, Blue, Violet, Amber, Pink, Slate, Red, Green }

    fun badgeColor(tokens: ThemeTokens, tone: BadgeTone): Int = when (tone) {
        BadgeTone.Teal -> 0xFF2DD4BF.toInt()
        BadgeTone.Blue -> 0xFF60A5FA.toInt()
        BadgeTone.Violet -> 0xFFA78BFA.toInt()
        BadgeTone.Amber -> 0xFFFBBF24.toInt()
        BadgeTone.Pink -> 0xFFF472B6.toInt()
        BadgeTone.Slate -> tokens.secondaryText
        BadgeTone.Red -> tokens.danger
        BadgeTone.Green -> tokens.success
    }

    /** A colored rounded-square icon badge. Used in category rows and status cards. */
    fun iconBadge(
        context: Context,
        tokens: ThemeTokens,
        iconRes: Int,
        tone: BadgeTone,
        sizeDp: Int = 36
    ): FrameLayout {
        val color = badgeColor(tokens, tone)
        val sizePx = dp(context, sizeDp)
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            background = Drawables.rounded(
                fill = ColorUtils.with(color, 0x26),
                radius = dp(context, 10).toFloat(),
                strokeColor = ColorUtils.with(color, 0x44),
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(color)
            val padding = dp(context, 7)
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }
        frame.addView(icon)
        return frame
    }

    // -------- text helpers --------

    fun largeTitle(context: Context, tokens: ThemeTokens, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyLargeTitle(this, tokens)
        }
    }

    fun title(context: Context, tokens: ThemeTokens, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyTitle(this, tokens)
        }
    }

    fun headline(context: Context, tokens: ThemeTokens, text: String, color: Int? = null): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyHeadline(this, tokens, color)
        }
    }

    fun body(context: Context, tokens: ThemeTokens, text: String, secondary: Boolean = true): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyCallout(this, tokens, secondary)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
        }
    }

    fun footnote(context: Context, tokens: ThemeTokens, text: String, secondary: Boolean = true): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyFootnote(this, tokens, secondary)
        }
    }

    fun overline(context: Context, tokens: ThemeTokens, text: String): TextView {
        return TextView(context).apply {
            this.text = text.uppercase()
            textSize = DesignTokens.Text.caption
            setTextColor(tokens.tertiaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.10f
            includeFontPadding = false
        }
    }

    // -------- header bars --------

    /** Hub header: large avatar + title + subtitle. */
    fun hubHeader(
        context: Context,
        tokens: ThemeTokens,
        titleText: String,
        subtitleText: String,
        avatarRes: Int = R.drawable.openclaw_bubble_logo,
        avatarSizeDp: Int = 40,
        onMenu: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = ImageView(context).apply {
            setImageResource(avatarRes)
            layoutParams = LinearLayout.LayoutParams(dp(context, avatarSizeDp), dp(context, avatarSizeDp))
            background = Drawables.circle(
                fill = ColorUtils.with(tokens.accent, 0x18),
                strokeColor = ColorUtils.with(tokens.accent, 0x66),
                strokeWidth = dp(context, 1)
            )
            val pad = dp(context, if (avatarSizeDp >= 60) 10 else 8)
            setPadding(pad, pad, pad, pad)
            setColorFilter(tokens.accent)
        }
        row.addView(avatar)

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, DesignTokens.Spacing.lg), 0, dp(context, DesignTokens.Spacing.sm), 0)
        }
        copy.addView(largeTitle(context, tokens, titleText).apply {
            textSize = if (avatarSizeDp >= 60) 32f else 29f
            letterSpacing = -0.015f
        })
        copy.addView(body(context, tokens, subtitleText).apply {
            setPadding(0, dp(context, DesignTokens.Spacing.xs + 1), 0, 0)
            textSize = if (avatarSizeDp >= 60) 16f else 14f
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (onMenu != null) {
            row.addView(iconButton(context, tokens, R.drawable.ic_more_vert, "Menu", onMenu))
        }

        return row
    }

    /** Sub-screen toolbar: back arrow + title (and optional trailing). */
    fun subscreenHeader(
        context: Context,
        tokens: ThemeTokens,
        titleText: String,
        onBack: () -> Unit,
        trailing: View? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, DesignTokens.Spacing.md))

            addView(iconButton(context, tokens, R.drawable.ic_arrow_back, "Back", onBack))
            addView(headline(context, tokens, titleText).apply {
                setPadding(dp(context, DesignTokens.Spacing.sm), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing)
        }
    }

    fun iconButton(
        context: Context,
        tokens: ThemeTokens,
        iconRes: Int,
        contentDescription: String,
        onClick: () -> Unit
    ): ImageView {
        val size = dp(context, 40)
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tokens.primaryText)
            this.contentDescription = contentDescription
            val pad = dp(context, 10)
            setPadding(pad, pad, pad, pad)
            background = Drawables.rounded(
                fill = Color.TRANSPARENT,
                radius = dp(context, 10).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // -------- status chips (4 across) --------

    enum class StatusTone { Good, Warn, Bad, Idle }

    fun statusToneColor(tokens: ThemeTokens, tone: StatusTone): Int = when (tone) {
        StatusTone.Good -> tokens.success
        StatusTone.Warn -> tokens.warning
        StatusTone.Bad -> tokens.danger
        StatusTone.Idle -> tokens.secondaryText
    }

    /** Single status card: icon well + label + colored indicator. */
    fun statusChip(
        context: Context,
        tokens: ThemeTokens,
        iconRes: Int,
        label: String,
        value: String,
        tone: StatusTone,
        fillCell: Boolean = false,
        iconSizeDp: Int = 12,
        highlighted: Boolean = false,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val dotColor = statusToneColor(tokens, tone)
        val iconWellSize = (iconSizeDp + 14).coerceAtLeast(30)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val baseBackground = Drawables.rounded(
                fill = if (highlighted) ColorUtils.with(dotColor, 0x16) else tokens.surface,
                radius = dp(context, 18).toFloat(),
                strokeColor = if (highlighted) ColorUtils.with(dotColor, 0x66) else tokens.border,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            background = if (onClick != null) {
                RippleDrawable(ColorStateList.valueOf(ColorUtils.with(dotColor, 0x22)), baseBackground, null)
            } else {
                baseBackground
            }
            setPadding(
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.sm)
            )
            if (fillCell) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            exposeToAccessibility(description = "$label $value")
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        val iconWell = FrameLayout(context).apply {
            background = Drawables.rounded(
                fill = ColorUtils.with(tokens.secondaryText, 0x16),
                radius = dp(context, 10).toFloat(),
                strokeColor = ColorUtils.with(tokens.secondaryText, 0x24),
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, iconWellSize), dp(context, iconWellSize))
        }
        iconWell.addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tokens.secondaryText)
            layoutParams = FrameLayout.LayoutParams(dp(context, iconSizeDp), dp(context, iconSizeDp), Gravity.CENTER)
        })
        container.addView(iconWell)

        val textRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(dp(context, 6), 0, 0, 0)
        }
        textRow.addView(TextView(context).apply {
            text = label
            textSize = if (fillCell) 15f else DesignTokens.Text.callout
            setTextColor(tokens.primaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textRow.addView(View(context).apply {
            background = Drawables.circle(fill = dotColor)
            layoutParams = LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply {
                marginStart = dp(context, DesignTokens.Spacing.sm)
            }
        })
        container.addView(textRow)

        return container
    }

    /** 2x2 grid of status chips with equal cell sizing. */
    fun statusChipGrid(context: Context, chips: List<View>, gridHeightPx: Int): LinearLayout {
        require(chips.size == 4) { "statusChipGrid expects exactly 4 chips" }
        val gap = dp(context, DesignTokens.Spacing.sm)
        val rowHeight = ((gridHeightPx - gap).coerceAtLeast(1)) / 2
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                gridHeightPx
            )
            addView(buildStatusChipGridRow(context, chips, 0, rowHeight, gap))
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gap))
            addView(buildStatusChipGridRow(context, chips, 2, rowHeight, gap))
        }
    }

    private fun buildStatusChipGridRow(
        context: Context,
        chips: List<View>,
        startIndex: Int,
        rowHeight: Int,
        gap: Int
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeight
            )
            addView(chips[startIndex], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(View(context), LinearLayout.LayoutParams(gap, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(chips[startIndex + 1], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    // -------- search bar --------

    fun searchBar(
        context: Context,
        tokens: ThemeTokens,
        hint: String,
        shortcut: String? = "\u2318K"
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(
                fill = tokens.surfaceInset,
                radius = dp(context, 14).toFloat(),
                strokeColor = tokens.borderSoft,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm)
            )
            minimumHeight = dp(context, 46)
        }

        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
        })

        val input = EditText(context).apply {
            this.hint = hint
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            setPadding(dp(context, DesignTokens.Spacing.sm + 2), 0, dp(context, DesignTokens.Spacing.sm), 0)
            tag = "search_input"
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (shortcut != null) {
            row.addView(kbdChip(context, tokens, shortcut))
        }

        return row
    }

    /** Small dark "keyboard" chip (e.g., \u2318K, \u21e71, esc). */
    fun kbdChip(context: Context, tokens: ThemeTokens, label: String): TextView {
        return TextView(context).apply {
            text = label
            textSize = DesignTokens.Text.caption
            setTextColor(tokens.secondaryText)
            typeface = Typeface.MONOSPACE
            background = Drawables.rounded(
                fill = tokens.surface,
                radius = dp(context, 6).toFloat(),
                strokeColor = tokens.border,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
            includeFontPadding = false
        }
    }

    // -------- category row (icon + title + subtitle + chevron) --------

    fun categoryRow(
        context: Context,
        tokens: ThemeTokens,
        iconRes: Int,
        tone: BadgeTone,
        titleText: String,
        subtitleText: String,
        onClick: () -> Unit,
        minHeightDp: Int = 64,
        iconSizeDp: Int = 38,
        fillRow: Boolean = false
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = darkCardBackground(context, tokens)
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            exposeToAccessibility(description = "$titleText, $subtitleText")
            if (!fillRow) {
                minimumHeight = dp(context, minHeightDp)
            }
        }

        row.addView(iconBadge(context, tokens, iconRes, tone, sizeDp = iconSizeDp))

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, DesignTokens.Spacing.md), 0, dp(context, DesignTokens.Spacing.sm), 0)
        }
        copy.addView(TextView(context).apply {
            text = titleText
            Typography.applyCallout(this, tokens)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        copy.addView(TextView(context).apply {
            text = subtitleText
            Typography.applyFootnote(this, tokens, secondary = true)
            setPadding(0, dp(context, 2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
        })

        return row
    }

    // -------- section header with optional trailing link --------

    fun sectionHeader(
        context: Context,
        tokens: ThemeTokens,
        titleText: String,
        trailingText: String? = null,
        onTrailing: (() -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, DesignTokens.Spacing.sm))

            addView(overline(context, tokens, titleText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (trailingText != null) {
                addView(TextView(context).apply {
                    text = trailingText
                    textSize = DesignTokens.Text.footnote
                    setTextColor(tokens.accent)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isClickable = true
                    isFocusable = true
                    onTrailing?.let { setOnClickListener { it() } }
                })
            }
        }
    }

    // -------- buttons --------

    enum class ButtonTone { Primary, Secondary, Outline, Danger }

    fun primaryButton(
        context: Context,
        tokens: ThemeTokens,
        text: String,
        iconRes: Int? = null,
        tone: ButtonTone = ButtonTone.Primary,
        onClick: () -> Unit
    ): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(context, DesignTokens.Sizes.action)
            isClickable = true
            isFocusable = true
            setPadding(
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.sm + 2),
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.sm + 2)
            )
            setOnClickListener { onClick() }
        }
        val textColor: Int
        when (tone) {
            ButtonTone.Primary -> {
                container.background = Drawables.rounded(tokens.accent, dp(context, 12).toFloat())
                textColor = tokens.accentInk
            }
            ButtonTone.Secondary -> {
                container.background = Drawables.rounded(
                    fill = tokens.surfaceInset,
                    radius = dp(context, 12).toFloat(),
                    strokeColor = tokens.border,
                    strokeWidth = dp(context, 1).coerceAtLeast(1)
                )
                textColor = tokens.primaryText
            }
            ButtonTone.Outline -> {
                container.background = Drawables.rounded(
                    fill = Color.TRANSPARENT,
                    radius = dp(context, 12).toFloat(),
                    strokeColor = tokens.border,
                    strokeWidth = dp(context, 1).coerceAtLeast(1)
                )
                textColor = tokens.primaryText
            }
            ButtonTone.Danger -> {
                container.background = Drawables.rounded(
                    fill = ColorUtils.with(tokens.danger, 0x22),
                    radius = dp(context, 12).toFloat(),
                    strokeColor = ColorUtils.with(tokens.danger, 0x55),
                    strokeWidth = dp(context, 1).coerceAtLeast(1)
                )
                textColor = tokens.danger
            }
        }
        if (iconRes != null) {
            container.addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(textColor)
                layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                    marginEnd = dp(context, DesignTokens.Spacing.sm)
                }
            })
        }
        container.addView(TextView(context).apply {
            this.text = text
            Typography.applyCallout(this, tokens)
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        return container
    }

    // -------- input field (labeled) --------

    fun labeledField(
        context: Context,
        tokens: ThemeTokens,
        label: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onEdited: ((String) -> Unit)? = null
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(overline(context, tokens, label))

        val input = EditText(context).apply {
            setText(value)
            this.inputType = inputType
            setSingleLine(inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            background = insetBackground(context, tokens)
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2)
            )
            minHeight = dp(context, DesignTokens.Sizes.action)
            tag = "input"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onEdited?.invoke(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, DesignTokens.Spacing.sm)
        })

        return column
    }

    // -------- segmented control --------

    class Segmented(
        val view: LinearLayout,
        private val tokens: ThemeTokens,
        private val context: Context,
        private val labels: List<String>,
        initial: Int
    ) {
        private var index: Int = initial.coerceIn(0, labels.lastIndex.coerceAtLeast(0))

        fun selectedIndex(): Int = index

        fun render() {
            view.removeAllViews()
            labels.forEachIndexed { i, label ->
                val active = i == index
                val tv = TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    minimumHeight = dp(context, 38)
                    setPadding(
                        dp(context, DesignTokens.Spacing.md),
                        dp(context, DesignTokens.Spacing.sm),
                        dp(context, DesignTokens.Spacing.md),
                        dp(context, DesignTokens.Spacing.sm)
                    )
                    Typography.applyCallout(this, tokens, secondary = !active)
                    typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
                    if (active) {
                        setTextColor(tokens.accentInk)
                        background = Drawables.rounded(tokens.accent, dp(context, 10).toFloat())
                    } else {
                        setTextColor(tokens.secondaryText)
                        background = null
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        index = i
                        render()
                    }
                }
                view.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    fun segmented(context: Context, tokens: ThemeTokens, labels: List<String>, selected: Int): Segmented {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Drawables.rounded(
                fill = tokens.surfaceInset,
                radius = dp(context, 12).toFloat(),
                strokeColor = tokens.borderSoft,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            val pad = dp(context, 4)
            setPadding(pad, pad, pad, pad)
        }
        val s = Segmented(container, tokens, context, labels, selected)
        s.render()
        return s
    }

    // -------- stepper with chip presets --------

    class Stepper(
        val view: LinearLayout,
        private val valueText: TextView,
        private val context: Context,
        private val tokens: ThemeTokens,
        private val min: Int,
        private val max: Int,
        private val step: Int,
        initial: Int
    ) {
        var value: Int = initial.coerceIn(min, max)
            private set

        fun set(newValue: Int) {
            value = newValue.coerceIn(min, max)
            valueText.text = value.toString()
        }

        fun increment() = set(value + step)
        fun decrement() = set(value - step)
    }

    fun stepper(context: Context, tokens: ThemeTokens, min: Int, max: Int, step: Int, initial: Int): Stepper {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(
                fill = tokens.surfaceInset,
                radius = dp(context, 12).toFloat(),
                strokeColor = tokens.borderSoft,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            val pad = dp(context, 8)
            setPadding(pad, pad, pad, pad)
        }

        val valueText = TextView(context).apply {
            text = initial.toString()
            gravity = Gravity.CENTER
            Typography.applyTitle(this, tokens)
            textSize = 24f
        }

        lateinit var stepperRef: Stepper

        val minus = circleIconButton(context, tokens, R.drawable.ic_minus) { stepperRef.decrement() }
        val plus = circleIconButton(context, tokens, R.drawable.ic_plus) { stepperRef.increment() }

        container.addView(minus)
        container.addView(valueText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(plus)

        stepperRef = Stepper(container, valueText, context, tokens, min, max, step, initial)
        return stepperRef
    }

    fun circleIconButton(context: Context, tokens: ThemeTokens, iconRes: Int, onClick: () -> Unit): ImageView {
        val size = dp(context, 36)
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tokens.primaryText)
            background = Drawables.circle(
                fill = tokens.surface,
                strokeColor = tokens.border,
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            val pad = dp(context, 8)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun chipRow(
        context: Context,
        tokens: ThemeTokens,
        labels: List<String>,
        values: List<Int>,
        current: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEachIndexed { index, label ->
            val v = values[index]
            val active = v == current
            row.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                minimumHeight = dp(context, 34)
                setPadding(0, dp(context, 6), 0, dp(context, 6))
                Typography.applyFootnote(this, tokens, secondary = !active)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = if (active) {
                    Drawables.rounded(
                        fill = ColorUtils.with(tokens.accent, 0x33),
                        radius = dp(context, 10).toFloat(),
                        strokeColor = ColorUtils.with(tokens.accent, 0x88),
                        strokeWidth = dp(context, 1).coerceAtLeast(1)
                    )
                } else {
                    Drawables.rounded(
                        fill = tokens.surface,
                        radius = dp(context, 10).toFloat(),
                        strokeColor = tokens.border,
                        strokeWidth = dp(context, 1).coerceAtLeast(1)
                    )
                }
                setTextColor(if (active) tokens.accent else tokens.secondaryText)
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(v) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(context, DesignTokens.Spacing.sm)
            })
        }
        return row
    }

    // -------- toggle row (icon + copy + Switch) --------

    fun toggleRow(
        context: Context,
        tokens: ThemeTokens,
        iconRes: Int,
        tone: BadgeTone,
        titleText: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconBadge(context, tokens, iconRes, tone, sizeDp = 36))

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, DesignTokens.Spacing.md), 0, dp(context, DesignTokens.Spacing.sm), 0)
        }
        copy.addView(TextView(context).apply {
            text = titleText
            Typography.applyCallout(this, tokens)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        if (!subtitle.isNullOrBlank()) {
            copy.addView(TextView(context).apply {
                text = subtitle
                Typography.applyFootnote(this, tokens, secondary = true)
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(tokens.primaryText)
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(tokens.accent, tokens.border)
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(sw)

        return row
    }

    // -------- checkbox-style row (label + check) --------

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
            minimumHeight = dp(context, 36)
        }
        val state = booleanArrayOf(checked)
        val icon = ImageView(context).apply {
            setImageResource(if (state[0]) R.drawable.ic_check_box else R.drawable.ic_check_box_outline)
            setColorFilter(if (state[0]) tokens.accent else tokens.secondaryText)
            layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22))
        }
        row.addView(icon)
        row.addView(TextView(context).apply {
            text = titleText
            Typography.applyCallout(this, tokens)
            setPadding(dp(context, DesignTokens.Spacing.sm + 2), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            state[0] = !state[0]
            icon.setImageResource(if (state[0]) R.drawable.ic_check_box else R.drawable.ic_check_box_outline)
            icon.setColorFilter(if (state[0]) tokens.accent else tokens.secondaryText)
            onChange(state[0])
        }
        return row
    }

    // -------- info banner --------

    fun infoBanner(context: Context, tokens: ThemeTokens, title: String, subtitle: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Drawables.rounded(
                fill = ColorUtils.with(tokens.accent, 0x1A),
                radius = dp(context, 12).toFloat(),
                strokeColor = ColorUtils.with(tokens.accent, 0x55),
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md - 2),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md - 2)
            )
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(tokens.accent)
            layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
        }
        row.addView(icon)
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, DesignTokens.Spacing.sm + 2), 0, 0, 0)
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

    // -------- file path row --------

    fun filePathRow(context: Context, tokens: ThemeTokens, path: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = insetBackground(context, tokens)
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2)
            )
            minimumHeight = dp(context, 48)
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_file)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
        })
        row.addView(TextView(context).apply {
            text = if (path.isBlank()) "(no path set)" else path
            Typography.applyFootnote(this, tokens, secondary = path.isBlank())
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(context, DesignTokens.Spacing.sm), 0, dp(context, DesignTokens.Spacing.sm), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(dp(context, 16), dp(context, 16))
        })
        return row
    }

    // -------- divider --------

    fun hairline(context: Context, tokens: ThemeTokens, topDp: Int = 0, bottomDp: Int = 0): View {
        return View(context).apply {
            setBackgroundColor(tokens.borderSoft)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(context, topDp)
                bottomMargin = dp(context, bottomDp)
            }
        }
    }
}

object ColorUtils {
    /** Apply an 8-bit alpha to an opaque color. */
    fun with(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }
}
