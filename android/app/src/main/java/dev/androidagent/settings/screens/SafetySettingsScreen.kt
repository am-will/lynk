package dev.androidagent.settings.screens

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.androidagent.AgentConfigStore
import dev.androidagent.DefaultSystemPrompt
import dev.androidagent.R
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object SafetySettingsScreen {

    interface Callbacks {
        fun openOverlaySettings()
        fun requestMicPermission()
        fun requestLocationPermission()
        fun openAccessibilitySettings()
        fun onBack()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val config = AgentConfigStore.load(activity)
        var promptDraft = config.systemPrompt
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(SettingsUi.toolbar(activity, "System", tokens, callbacks::onBack))

        val promptSummary = SettingsUi.body(activity, SettingsUi.systemPromptPreview(promptDraft), tokens).apply {
            background = Drawables.glassInset(activity, tokens, DesignTokens.Radius.md)
            setPadding(
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2)
            )
        }

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "System prompt", "Default instructions sent to the selected harness.", tokens))
            addView(promptSummary, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(
                SettingsUi.actionButton(activity, "Edit System Prompt", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens) {
                    showEditor(activity, tokens, promptDraft) { updated ->
                        promptDraft = updated
                        promptSummary.text = SettingsUi.systemPromptPreview(promptDraft)
                    }
                }.exposeToAccessibility(R.id.openclaw_system_prompt_button, "Edit system prompt"),
                SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2)
            )
        }, SettingsUi.stackedParams(activity))

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Permissions", "App permissions used by the overlay, voice, and location-aware flows.", tokens))
            addView(SettingsUi.actionButton(activity, "Grant Overlay Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::openOverlaySettings).apply {
                exposeToAccessibility(R.id.openclaw_overlay_permission_button, "Grant overlay permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(SettingsUi.actionButton(activity, "Grant Microphone Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::requestMicPermission).apply {
                exposeToAccessibility(R.id.openclaw_microphone_permission_button, "Grant microphone permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2))
            addView(SettingsUi.actionButton(activity, "Grant Location Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::requestLocationPermission).apply {
                exposeToAccessibility(R.id.openclaw_location_permission_button, "Grant location permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2))
        }, SettingsUi.stackedParams(activity))

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Phone Control", "Accessibility enables screen observation and command execution.", tokens))
            addView(SettingsUi.actionButton(activity, "Open Accessibility Settings", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::openAccessibilitySettings).apply {
                exposeToAccessibility(R.id.openclaw_accessibility_settings_button, "Open accessibility settings")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "Risky phone actions still require the confirmation overlay at runtime.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Save", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                AgentConfigStore.save(activity, config.copy(systemPrompt = promptDraft.trim().ifBlank { DefaultSystemPrompt.text }))
                callbacks.onBack()
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.xl)
        )

        return root
    }

    private fun showEditor(activity: Activity, tokens: ThemeTokens, initialText: String, onSave: (String) -> Unit) {
        val editor = EditText(activity).apply {
            exposeToAccessibility(R.id.openclaw_system_prompt_editor, "System prompt editor")
            setText(initialText)
            minLines = 10
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            background = Drawables.glassInset(activity, tokens, DesignTokens.Radius.md)
            setPadding(
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2)
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("System Prompt")
            .setView(ScrollView(activity).apply {
                setPadding(SettingsUi.dp(activity, DesignTokens.Spacing.xl), SettingsUi.dp(activity, DesignTokens.Spacing.lg), SettingsUi.dp(activity, DesignTokens.Spacing.xl), 0)
                addView(editor)
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ -> onSave(DefaultSystemPrompt.text) }
            .setPositiveButton("Save") { _, _ ->
                onSave(editor.text.toString().trim().ifBlank { DefaultSystemPrompt.text })
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Drawables.glassSurface(activity, tokens, DesignTokens.Radius.xl))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(tokens.accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(tokens.secondaryText)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(tokens.secondaryText)
        }
        dialog.show()
    }
}
