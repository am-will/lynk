package dev.androidagent.settings.screens

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import dev.androidagent.AgentConfigStore
import dev.androidagent.CodexWorkspacePaths
import dev.androidagent.LocalModelBackend
import dev.androidagent.R
import dev.androidagent.settings.SettingsButtonTone
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object RuntimeSettingsScreen {

    interface Callbacks {
        fun onSaved()
        fun onImportRequested(pathField: EditText)
        fun onBack()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val config = AgentConfigStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(SettingsUi.toolbar(activity, "Harness", tokens, callbacks::onBack))

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

        val codexWorkspaceInput = SettingsUi.configField(
            activity,
            "Default workspace",
            CodexWorkspacePaths.display(config.codexWorkspacePath),
            tokens,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Codex Default Workspace", "New Codex chats start here when Codex is selected.", tokens))
            addView(SettingsUi.labeledField(activity, "Workspace path", codexWorkspaceInput, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "Use ~/ as shorthand for your user folder.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        val localModelPathInput = SettingsUi.configField(activity, "Model file", config.localModelPath, tokens).apply {
            exposeToAccessibility(R.id.openclaw_local_model_path_field, "Local LiteRT model path")
        }
        val localBackends = LocalModelBackend.values().toList()
        val localBackendSpinner = SettingsUi.styledSpinner(
            activity,
            localBackends.map { it.label },
            localBackends.indexOf(config.localModelBackend).coerceAtLeast(0),
            tokens
        )
        val localContextInput = SettingsUi.configField(
            activity,
            "Context tokens",
            config.localContextTokens.toString(),
            tokens,
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            exposeToAccessibility(R.id.openclaw_local_context_field, "Local context window")
        }
        val localDeveloperTools = SettingsUi.harnessCheckBox(
            activity,
            "Enable developer tools",
            config.localDeveloperToolsEnabled,
            "Enable local model file writes, terminal, and developer tools",
            tokens,
            R.id.openclaw_local_developer_tools_checkbox
        )

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Local Models", "Import and tune the on-device LiteRT-LM harness.", tokens))
            addView(SettingsUi.labeledField(activity, "Model file", localModelPathInput, tokens, DesignTokens.Spacing.md))
            addView(
                SettingsUi.actionButton(activity, "Import Local Model", SettingsButtonTone.Secondary, tokens) {
                    callbacks.onImportRequested(localModelPathInput)
                }.exposeToAccessibility(R.id.openclaw_local_model_import_button, "Import local LiteRT model"),
                SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2)
            )
            addView(SettingsUi.labeledField(activity, "Backend", localBackendSpinner, tokens))
            addView(SettingsUi.labeledField(activity, "Context window", localContextInput, tokens))
            addView(localDeveloperTools, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "Local phone tools remain governed by System permissions and phone-control settings.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Save", SettingsButtonTone.Primary, tokens) {
                val saved = config.copy(
                    openClawHarnessEnabled = openClaw.isChecked,
                    hermesHarnessEnabled = hermes.isChecked,
                    codexHarnessEnabled = codex.isChecked,
                    experimentalLocalModelsEnabled = local.isChecked,
                    localModelPath = localModelPathInput.text.toString().trim(),
                    localModelBackend = localBackends.getOrElse(localBackendSpinner.selectedItemPosition) { LocalModelBackend.Cpu },
                    localContextTokens = localContextInput.text.toString().toIntOrNull()?.coerceIn(512, 131_072)
                        ?: config.localContextTokens,
                    localDeveloperToolsEnabled = localDeveloperTools.isChecked,
                    codexWorkspacePath = CodexWorkspacePaths.normalizeInput(codexWorkspaceInput.text?.toString())
                )
                AgentConfigStore.save(activity, saved)
                callbacks.onSaved()
                callbacks.onBack()
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.xl)
        )

        return root
    }
}
