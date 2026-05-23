package dev.androidagent.settings.screens

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentModelOptions
import dev.androidagent.ChatActiveSendMode
import dev.androidagent.R
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens

object RuntimeSettingsScreen {

    fun build(activity: Activity, tokens: ThemeTokens, onBack: () -> Unit): View {
        val config = AgentConfigStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(SettingsUi.toolbar(activity, "Runtime", tokens, onBack))

        val openClaw = SettingsUi.harnessCheckBox(activity, "OpenClaw", config.openClawHarnessEnabled, "Enable OpenClaw harness", tokens, R.id.openclaw_harness_openclaw_checkbox)
        val hermes = SettingsUi.harnessCheckBox(activity, "Hermes", config.hermesHarnessEnabled, "Enable Hermes harness", tokens, R.id.openclaw_harness_hermes_checkbox)
        val codex = SettingsUi.harnessCheckBox(activity, "Codex", config.codexHarnessEnabled, "Enable Codex harness", tokens, R.id.openclaw_harness_codex_checkbox)
        val local = SettingsUi.harnessCheckBox(activity, "Local LiteRT-LM (experimental)", config.experimentalLocalModelsEnabled, "Enable local harness", tokens, R.id.openclaw_harness_local_litert_checkbox)

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Backends", "Disabled harnesses are hidden from the model picker.", tokens))
            addView(openClaw, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(hermes, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(codex, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(local, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        val modelOptions = AgentModelOptions.models.map { it.label }
        val modelSpinner = SettingsUi.styledSpinner(
            activity,
            modelOptions,
            AgentModelOptions.models.indexOfFirst { it.id == config.model }.coerceAtLeast(0),
            tokens
        )
        val reasoningOptions = AgentModelOptions.reasoningEfforts.map { it.label }
        val reasoningSpinner = SettingsUi.styledSpinner(
            activity,
            reasoningOptions,
            AgentModelOptions.reasoningEfforts.indexOfFirst { it.id == config.reasoningEffort }.coerceAtLeast(0),
            tokens
        )
        val sendModes = ChatActiveSendMode.values().toList()
        val sendModeSpinner = SettingsUi.styledSpinner(
            activity,
            sendModes.map { it.label },
            sendModes.indexOf(config.activeSendMode).coerceAtLeast(0),
            tokens
        )

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Defaults", "Default model and chat behavior for new sessions.", tokens))
            addView(SettingsUi.labeledField(activity, "Default model", modelSpinner, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.labeledField(activity, "Default reasoning", reasoningSpinner, tokens))
            addView(SettingsUi.labeledField(activity, "Active send mode", sendModeSpinner, tokens))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Save", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                val saved = config.copy(
                    openClawHarnessEnabled = openClaw.isChecked,
                    hermesHarnessEnabled = hermes.isChecked,
                    codexHarnessEnabled = codex.isChecked,
                    experimentalLocalModelsEnabled = local.isChecked,
                    model = AgentModelOptions.models.getOrElse(modelSpinner.selectedItemPosition) { AgentModelOptions.models.first() }.id,
                    reasoningEffort = AgentModelOptions.reasoningEfforts.getOrElse(reasoningSpinner.selectedItemPosition) { AgentModelOptions.reasoningEfforts.first() }.id,
                    activeSendMode = sendModes.getOrElse(sendModeSpinner.selectedItemPosition) { ChatActiveSendMode.Steer }
                )
                AgentConfigStore.save(activity, saved)
                onBack()
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.xl)
        )

        return root
    }
}
