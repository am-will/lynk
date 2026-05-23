package dev.androidagent.settings.screens

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import dev.androidagent.AgentConfigStore
import dev.androidagent.LocalModelBackend
import dev.androidagent.R
import dev.androidagent.settings.ColorUtils
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsComponents.BadgeTone
import dev.androidagent.settings.SettingsComponents.ButtonTone
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object LocalModelSettingsScreen {

    interface Callbacks {
        fun onSaved()
        fun onBack()
        fun onImportRequested(pathField: EditText)
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val config = AgentConfigStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // Toolbar
        root.addView(SettingsComponents.subscreenHeader(
            context = activity,
            tokens = tokens,
            titleText = "Local LiteRT-LM",
            onBack = callbacks::onBack
        ))

        // Active pill row
        val activePill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, SettingsComponents.dp(activity, DesignTokens.Spacing.lg))
        }
        activePill.addView(android.widget.TextView(activity).apply {
            text = "Active"
            setTextColor(tokens.accentInk)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.footnote
            setPadding(
                SettingsComponents.dp(activity, DesignTokens.Spacing.md - 2),
                SettingsComponents.dp(activity, 4),
                SettingsComponents.dp(activity, DesignTokens.Spacing.md - 2),
                SettingsComponents.dp(activity, 4)
            )
            background = Drawables.rounded(tokens.accent, SettingsComponents.dp(activity, 999).toFloat())
        })
        activePill.addView(android.widget.TextView(activity).apply {
            text = "On-device language model"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            setPadding(SettingsComponents.dp(activity, DesignTokens.Spacing.sm + 2), 0, 0, 0)
        })
        root.addView(activePill)

        // Path input (will be passed to import callback)
        val pathInput = EditText(activity).apply {
            setText(config.localModelPath)
            setSingleLine(true)
            visibility = View.GONE
            exposeToAccessibility(R.id.openclaw_local_model_path_field, "Local LiteRT model path")
        }

        // Import model card
        root.addView(buildImportCard(activity, tokens, callbacks, pathInput, config.localModelPath))

        // Backend card
        val backends = LocalModelBackend.values().toList()
        val backendSegmented = SettingsComponents.segmented(
            activity, tokens,
            backends.map { it.label },
            backends.indexOf(config.localModelBackend).coerceAtLeast(0)
        )
        root.addView(buildBackendCard(activity, tokens, backendSegmented), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        // Context window card
        val contextStepper = SettingsComponents.stepper(
            activity, tokens,
            min = 512, max = 131_072, step = 512,
            initial = config.localContextTokens
        )
        contextStepper.view.exposeToAccessibility(R.id.openclaw_local_context_field, "Local context window")
        val chipPresets = listOf("2K" to 2048, "4K" to 4096, "8K" to 8192, "16K" to 16_384, "32K" to 32_768)
        val chipRow = SettingsComponents.chipRow(
            activity, tokens,
            chipPresets.map { it.first },
            chipPresets.map { it.second },
            current = config.localContextTokens
        ) { value ->
            contextStepper.set(value)
        }
        root.addView(buildContextCard(activity, tokens, contextStepper, chipRow), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        // Dev tools toggle card
        var devToolsEnabled = config.localDeveloperToolsEnabled
        root.addView(buildDevToolsCard(activity, tokens, devToolsEnabled) { devToolsEnabled = it }, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        // Local workspace path card (read-only label)
        root.addView(buildWorkspaceCard(activity, tokens), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        // Phone tools card with check rows
        val androidToolsState = booleanArrayOf(true)
        val workspaceToolsState = booleanArrayOf(true)
        root.addView(buildPhoneToolsCard(activity, tokens, androidToolsState, workspaceToolsState), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        // Info banner
        root.addView(
            SettingsComponents.infoBanner(
                activity, tokens,
                "Local models run entirely on this device.",
                "No data leaves your phone."
            ),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg)
        )

        // Save (auto-save on back, but provide explicit Save too)
        root.addView(
            SettingsComponents.primaryButton(activity, tokens, "Save", tone = ButtonTone.Primary) {
                val saved = config.copy(
                    experimentalLocalModelsEnabled = true,
                    localModelPath = pathInput.text.toString().trim().ifBlank { config.localModelPath },
                    localModelBackend = backends.getOrElse(backendSegmented.selectedIndex()) { LocalModelBackend.Cpu },
                    localContextTokens = contextStepper.value,
                    localDeveloperToolsEnabled = devToolsEnabled
                )
                AgentConfigStore.save(activity, saved)
                callbacks.onSaved()
                callbacks.onBack()
            },
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg)
        )

        return root
    }

    private fun buildImportCard(
        activity: Activity,
        tokens: ThemeTokens,
        callbacks: Callbacks,
        pathInput: EditText,
        currentPath: String
    ): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(SettingsComponents.iconBadge(activity, tokens, R.drawable.ic_file, BadgeTone.Teal, sizeDp = 40))
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SettingsComponents.dp(activity, DesignTokens.Spacing.md), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm), 0)
        }
        copy.addView(android.widget.TextView(activity).apply {
            text = "Import model"
            setTextColor(tokens.primaryText)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        copy.addView(android.widget.TextView(activity).apply {
            text = "Add a .task or .litertlm model file"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(SettingsComponents.primaryButton(activity, tokens, "Import", tone = ButtonTone.Primary) {
            callbacks.onImportRequested(pathInput)
        }.exposeToAccessibility(R.id.openclaw_local_model_import_button, "Import local LiteRT model"))

        card.addView(row)
        // Hidden text field driver
        card.addView(pathInput)
        return card
    }

    private fun buildBackendCard(activity: Activity, tokens: ThemeTokens, segmented: SettingsComponents.Segmented): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        card.addView(android.widget.TextView(activity).apply {
            text = "Backend"
            setTextColor(tokens.primaryText)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        card.addView(android.widget.TextView(activity).apply {
            text = "Select compute backend"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        card.addView(segmented.view, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))
        return card
    }

    private fun buildContextCard(
        activity: Activity,
        tokens: ThemeTokens,
        stepper: SettingsComponents.Stepper,
        chipRow: LinearLayout
    ): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        card.addView(android.widget.TextView(activity).apply {
            text = "Context window (tokens)"
            setTextColor(tokens.primaryText)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        card.addView(android.widget.TextView(activity).apply {
            text = "Larger context uses more memory"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        card.addView(stepper.view, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))
        card.addView(chipRow, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm + 2))
        return card
    }

    private fun buildDevToolsCard(activity: Activity, tokens: ThemeTokens, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        card.addView(SettingsComponents.toggleRow(
            context = activity,
            tokens = tokens,
            iconRes = R.drawable.ic_terminal,
            tone = BadgeTone.Teal,
            titleText = "Enable developer tools",
            subtitle = "Allow file writes, terminal, and dev tools",
            checked = checked,
            onChange = onChange
        ).also { it.exposeToAccessibility(R.id.openclaw_local_developer_tools_checkbox, "Enable developer tools") })
        return card
    }

    private fun buildWorkspaceCard(activity: Activity, tokens: ThemeTokens): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        card.addView(android.widget.TextView(activity).apply {
            text = "Local workspace"
            setTextColor(tokens.primaryText)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        card.addView(
            SettingsComponents.filePathRow(activity, tokens, "/storage/emulated/0/AndroidAgent/workspace"),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm)
        )
        return card
    }

    private fun buildPhoneToolsCard(
        activity: Activity,
        tokens: ThemeTokens,
        androidState: BooleanArray,
        workspaceState: BooleanArray
    ): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        card.addView(android.widget.TextView(activity).apply {
            text = "Phone tools (local)"
            setTextColor(tokens.primaryText)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        card.addView(android.widget.TextView(activity).apply {
            text = "Tools available to the local model"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        card.addView(SettingsComponents.checkRow(activity, tokens, "Android phone tools", androidState[0]) { androidState[0] = it },
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))
        card.addView(SettingsComponents.checkRow(activity, tokens, "App-private workspace tools", workspaceState[0]) { workspaceState[0] = it },
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.xs))
        return card
    }
}
