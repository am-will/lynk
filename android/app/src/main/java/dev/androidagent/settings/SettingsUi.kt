package dev.androidagent.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.labelFor
import dev.androidagent.ui.updateAccessibilityState

enum class SettingsButtonTone {
    Primary,
    Secondary
}

object SettingsUi {

    fun dp(context: Context, value: Int): Int = DesignTokens.dp(context, value)

    fun tokens(context: Context): ThemeTokens = DesignTokens.resolve(context)

    fun card(context: Context, tokens: ThemeTokens): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = SettingsComponents.darkCardBackground(context, tokens)
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.md)
            )
        }
    }

    fun title(context: Context, text: String, tokens: ThemeTokens, large: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            if (large) Typography.applyLargeTitle(this, tokens) else Typography.applyTitle(this, tokens)
        }
    }

    fun body(context: Context, text: String, tokens: ThemeTokens, secondary: Boolean = true): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyCallout(this, tokens, secondary = secondary)
            setLineSpacing(dp(context, 2).toFloat(), 1.0f)
        }
    }

    fun fieldLabel(context: Context, text: String, tokens: ThemeTokens): TextView {
        return TextView(context).apply {
            this.text = text
            Typography.applyFootnote(this, tokens, secondary = true)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
        }
    }

    fun sectionHeader(context: Context, titleText: String, subtitle: String, tokens: ThemeTokens): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(context, titleText, tokens))
            addView(body(context, subtitle, tokens).apply {
                setPadding(0, dp(context, DesignTokens.Spacing.sm), 0, 0)
            })
        }
    }

    fun actionButton(
        context: Context,
        text: String,
        tone: SettingsButtonTone,
        tokens: ThemeTokens,
        onClick: () -> Unit
    ): TextView {
        val (bg, textColor) = when (tone) {
            SettingsButtonTone.Primary ->
                Drawables.rounded(tokens.accent, dp(context, SettingsComponents.CONTAINER_RADIUS).toFloat()) to tokens.accentInk
            SettingsButtonTone.Secondary ->
                Drawables.rounded(
                    fill = tokens.surfaceInset,
                    radius = dp(context, SettingsComponents.CONTAINER_RADIUS).toFloat(),
                    strokeColor = tokens.border,
                    strokeWidth = dp(context, 1).coerceAtLeast(1)
                ) to tokens.primaryText
        }
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            Typography.applyCallout(this, tokens)
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.sm + 2),
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.sm + 2)
            )
            background = bg
            isClickable = true
            isFocusable = true
            minHeight = dp(context, DesignTokens.Sizes.action)
            setOnClickListener { onClick() }
        }
    }

    fun configField(
        context: Context,
        hint: String,
        value: String,
        tokens: ThemeTokens,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(context).apply {
            this.hint = hint
            setText(value)
            setSingleLine((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0)
            this.inputType = inputType
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            background = SettingsComponents.insetBackground(context, tokens)
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm + 2)
            )
            minHeight = dp(context, DesignTokens.Sizes.action)
        }
    }

    fun styledSpinner(context: Context, items: List<String>, selection: Int, tokens: ThemeTokens): Spinner {
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(tokens.primaryText)
                    textSize = DesignTokens.Text.callout
                    setPadding(dp(context, DesignTokens.Spacing.sm), 0, dp(context, DesignTokens.Spacing.sm), 0)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(tokens.primaryText)
                    setBackgroundColor(tokens.surfaceElevated)
                    textSize = DesignTokens.Text.callout
                    setPadding(
                        dp(context, DesignTokens.Spacing.lg),
                        dp(context, DesignTokens.Spacing.md),
                        dp(context, DesignTokens.Spacing.lg),
                        dp(context, DesignTokens.Spacing.md)
                    )
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        return Spinner(context).apply {
            this.adapter = adapter
            setSelection(selection)
            background = SettingsComponents.insetBackground(context, tokens)
            setPadding(dp(context, DesignTokens.Spacing.md), 0, dp(context, DesignTokens.Spacing.md), 0)
            minimumHeight = dp(context, DesignTokens.Sizes.action)
        }
    }

    fun harnessCheckBox(
        context: Context,
        text: String,
        checked: Boolean,
        description: String,
        tokens: ThemeTokens,
        viewId: Int? = null
    ): CheckBox {
        return CheckBox(context).apply {
            this.text = text
            isChecked = checked
            setTextColor(tokens.primaryText)
            buttonTintList = android.content.res.ColorStateList.valueOf(tokens.accent)
            if (viewId != null) {
                exposeToAccessibility(viewId = viewId, description = description)
            } else {
                exposeToAccessibility(description = description)
            }
        }
    }

    fun stackedParams(context: Context, topMargin: Int = DesignTokens.Spacing.lg): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = dp(context, topMargin)
        }
    }

    fun toolbar(context: Context, titleText: String, tokens: ThemeTokens, onBack: () -> Unit): LinearLayout {
        return SettingsComponents.subscreenHeader(context, tokens, titleText, onBack)
    }

    fun statusChip(context: Context, label: String, value: String, color: Int, tokens: ThemeTokens): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Drawables.rounded(
                fill = tint(color, if (tokens.isDark) 0.20f else 0.12f),
                radius = dp(context, SettingsComponents.CONTAINER_RADIUS).toFloat(),
                strokeColor = tint(color, 0.40f),
                strokeWidth = dp(context, 1).coerceAtLeast(1)
            )
            setPadding(dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.sm))
            minimumWidth = dp(context, 72)

            addView(TextView(context).apply {
                text = label
                textSize = DesignTokens.Text.caption
                setTextColor(tokens.secondaryText)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = value
                textSize = DesignTokens.Text.footnote
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(color)
                gravity = Gravity.CENTER
                exposeToAccessibility(description = "$label status", stateDescription = value)
            })
        }
    }

    fun settingsRow(
        context: Context,
        titleText: String,
        subtitle: String,
        tokens: ThemeTokens,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.glassInset(context, tokens, SettingsComponents.CONTAINER_RADIUS)
            setPadding(
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.md + 2),
                dp(context, DesignTokens.Spacing.lg),
                dp(context, DesignTokens.Spacing.md + 2)
            )
            minimumHeight = dp(context, DesignTokens.Sizes.pickerRow)
            isClickable = true
            isFocusable = true
            exposeToAccessibility(description = "$titleText, $subtitle")
            setOnClickListener { onClick() }

            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = titleText
                    Typography.applyCallout(this, tokens)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(body(context, subtitle, tokens).apply {
                    textSize = DesignTokens.Text.footnote
                    setPadding(0, dp(context, 3), 0, 0)
                })
            }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = "›"
                textSize = DesignTokens.Text.title
                setTextColor(tokens.tertiaryText)
            })
        }
    }

    fun labeledField(
        context: Context,
        label: String,
        field: View,
        tokens: ThemeTokens,
        topMargin: Int = DesignTokens.Spacing.lg
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (topMargin > 0) {
                layoutParams = stackedParams(context, topMargin)
            }
            addView(fieldLabel(context, label, tokens).apply { labelFor(field) })
            addView(field, stackedParams(context, DesignTokens.Spacing.sm))
        }
    }

    fun tint(color: Int, amount: Float): Int {
        return Color.argb(
            (255 * amount).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    fun systemPromptPreview(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return if (normalized.length <= 140) normalized else "${normalized.take(137)}..."
    }
}
