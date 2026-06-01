package dev.androidagent.settings.screens

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import dev.androidagent.AgentConfig
import dev.androidagent.AgentConfigStore
import dev.androidagent.CodexWorkspacePaths
import dev.androidagent.LocalModelBackend
import dev.androidagent.R
import dev.androidagent.chat.ChatModelCatalog
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.settings.DiagnosticsBackendSnapshot
import dev.androidagent.settings.SettingsButtonTone
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object RuntimeSettingsScreen {

    interface Callbacks {
        fun onSettingsChanged()
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
        val opencode = SettingsUi.harnessCheckBox(activity, "OpenCode", config.opencodeHarnessEnabled, "Enable OpenCode harness", tokens, R.id.openclaw_harness_opencode_checkbox)
        val pi = SettingsUi.harnessCheckBox(activity, "Pi", config.piHarnessEnabled, "Enable Pi harness", tokens, R.id.openclaw_harness_pi_checkbox)
        val local = SettingsUi.harnessCheckBox(activity, "Local LiteRT-LM (experimental)", config.experimentalLocalModelsEnabled, "Enable local harness", tokens, R.id.openclaw_harness_local_litert_checkbox)

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Backends", "Disabled harnesses are hidden from the model picker.", tokens))
            addView(openClaw, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(hermes, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(codex, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(opencode, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(pi, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(local, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        val defaultModelControls = mutableListOf<DefaultModelControl>()
        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Default Models", "Select the model used when switching to a harness.", tokens))
            val enabledRows = addDefaultModelRows(
                activity = activity,
                tokens = tokens,
                config = config,
                controls = defaultModelControls
            )
            if (enabledRows == 0) {
                addView(
                    SettingsUi.body(activity, "Enable a host bridge harness above to choose default models.", tokens),
                    SettingsUi.stackedParams(activity, DesignTokens.Spacing.md)
                )
            }
        }, SettingsUi.stackedParams(activity))

        val workspaceControls = root.addWorkspaceCards(activity, tokens, config)

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

        fun saveCurrent() {
            AgentConfigStore.save(
                activity,
                AgentConfigStore.load(activity).copy(
                    openClawHarnessEnabled = openClaw.isChecked,
                    hermesHarnessEnabled = hermes.isChecked,
                    codexHarnessEnabled = codex.isChecked,
                    opencodeHarnessEnabled = opencode.isChecked,
                    piHarnessEnabled = pi.isChecked,
                    openClawDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_OPENCLAW,
                        config.openClawDefaultModel
                    ),
                    hermesDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_HERMES,
                        config.hermesDefaultModel
                    ),
                    codexDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_CODEX,
                        config.codexDefaultModel
                    ),
                    opencodeDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_OPENCODE,
                        config.opencodeDefaultModel
                    ),
                    piDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_PI,
                        config.piDefaultModel
                    ),
                    experimentalLocalModelsEnabled = local.isChecked,
                    localModelPath = localModelPathInput.text.toString().trim(),
                    localModelBackend = localBackends.getOrElse(localBackendSpinner.selectedItemPosition) { LocalModelBackend.Cpu },
                    localContextTokens = localContextInput.text.toString().toIntOrNull()?.coerceIn(512, 131_072)
                        ?: config.localContextTokens,
                    localDeveloperToolsEnabled = localDeveloperTools.isChecked,
                    codexWorkspacePath = workspaceControls.workspacePath(AgentConfig.HARNESS_CODEX),
                    opencodeWorkspacePath = workspaceControls.workspacePath(AgentConfig.HARNESS_OPENCODE),
                    piWorkspacePath = workspaceControls.workspacePath(AgentConfig.HARNESS_PI)
                )
            )
            callbacks.onSettingsChanged()
        }

        listOf(openClaw, hermes, codex, opencode, pi, local, localDeveloperTools).forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ -> saveCurrent() }
        }
        defaultModelControls.forEach { control ->
            control.spinner?.let { SettingsUi.onSpinnerSelectionChanged(it) { saveCurrent() } }
        }
        workspaceControls.forEach { control ->
            SettingsUi.onTextChanged(control.input) { saveCurrent() }
        }
        SettingsUi.onTextChanged(localModelPathInput) { saveCurrent() }
        SettingsUi.onSpinnerSelectionChanged(localBackendSpinner) { saveCurrent() }
        SettingsUi.onTextChanged(localContextInput) { saveCurrent() }

        return root
    }

    private data class DefaultModelControl(
        val harnessId: String,
        val models: List<ChatModelOption>,
        val spinner: Spinner?
    )

    private data class WorkspaceControl(
        val harnessId: String,
        val input: EditText
    )

    private data class WorkspaceSpec(
        val harnessId: String,
        val title: String,
        val description: String,
        val path: String
    )

    private fun LinearLayout.addWorkspaceCards(
        activity: Activity,
        tokens: ThemeTokens,
        config: AgentConfig
    ): List<WorkspaceControl> {
        return workspaceSpecs(config).map { spec ->
            val input = SettingsUi.configField(
                activity,
                "Default workspace",
                CodexWorkspacePaths.editorText(spec.path),
                tokens,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ).apply {
                keepCodexHomePrefix(this)
            }
            addView(SettingsUi.card(activity, tokens).apply {
                addView(SettingsUi.sectionHeader(activity, spec.title, spec.description, tokens))
                addView(SettingsUi.labeledField(activity, "Workspace path", input, tokens, DesignTokens.Spacing.md))
                addView(SettingsUi.body(activity, "Leave blank for QuickChats. The ~/ prefix means your user folder.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            }, SettingsUi.stackedParams(activity))
            WorkspaceControl(spec.harnessId, input)
        }
    }

    private fun workspaceSpecs(config: AgentConfig): List<WorkspaceSpec> = listOf(
        WorkspaceSpec(
            harnessId = AgentConfig.HARNESS_CODEX,
            title = "Codex Default Workspace",
            description = "New Codex chats start here when Codex is selected.",
            path = config.codexWorkspacePath
        ),
        WorkspaceSpec(
            harnessId = AgentConfig.HARNESS_OPENCODE,
            title = "OpenCode Default Workspace",
            description = "New OpenCode chats start here when OpenCode is selected.",
            path = config.opencodeWorkspacePath
        ),
        WorkspaceSpec(
            harnessId = AgentConfig.HARNESS_PI,
            title = "Pi Default Workspace",
            description = "New Pi chats start here when Pi is selected.",
            path = config.piWorkspacePath
        )
    )

    private fun List<WorkspaceControl>.workspacePath(harnessId: String): String {
        return firstOrNull { it.harnessId == harnessId }
            ?.input
            ?.text
            ?.toString()
            ?.let(CodexWorkspacePaths::normalizeInput)
            .orEmpty()
    }

    private fun LinearLayout.addDefaultModelRows(
        activity: Activity,
        tokens: ThemeTokens,
        config: AgentConfig,
        controls: MutableList<DefaultModelControl>
    ): Int {
        val snapshot = DiagnosticsBackendSnapshot.current()
        val specs = listOf(
            DefaultModelSpec(
                harnessId = AgentConfig.HARNESS_OPENCLAW,
                label = "OpenClaw default",
                enabled = config.openClawHarnessEnabled,
                viewId = R.id.openclaw_default_model_spinner
            ),
            DefaultModelSpec(
                harnessId = AgentConfig.HARNESS_HERMES,
                label = "Hermes default",
                enabled = config.hermesHarnessEnabled,
                viewId = R.id.openclaw_hermes_default_model_spinner
            ),
            DefaultModelSpec(
                harnessId = AgentConfig.HARNESS_CODEX,
                label = "Codex default",
                enabled = config.codexHarnessEnabled,
                viewId = R.id.openclaw_codex_default_model_spinner
            ),
            DefaultModelSpec(
                harnessId = AgentConfig.HARNESS_OPENCODE,
                label = "OpenCode default",
                enabled = config.opencodeHarnessEnabled,
                viewId = R.id.openclaw_opencode_default_model_spinner
            ),
            DefaultModelSpec(
                harnessId = AgentConfig.HARNESS_PI,
                label = "Pi default",
                enabled = config.piHarnessEnabled,
                viewId = R.id.openclaw_pi_default_model_spinner
            )
        )
        var visibleRows = 0
        specs.filter { it.enabled }.forEach { spec ->
            visibleRows += 1
            val models = snapshot.modelsByHarness[spec.harnessId]
                .orEmpty()
                .filter { it.available != false }
                .distinctBy { it.id }
            if (models.isEmpty()) {
                val spinner = SettingsUi.styledSpinner(
                    activity,
                    listOf("Connect to the bridge to load models"),
                    0,
                    tokens
                ).apply {
                    isEnabled = false
                    alpha = 0.55f
                    exposeToAccessibility(spec.viewId, "${spec.label}: connect to the bridge to load models")
                }
                addView(SettingsUi.labeledField(activity, spec.label, spinner, tokens, DesignTokens.Spacing.md))
                controls.add(DefaultModelControl(spec.harnessId, emptyList(), null))
            } else {
                val selected = ChatModelCatalog.defaultModelForHarness(
                    harnessId = spec.harnessId,
                    configuredDefaultModel = config.defaultModelForHarness(spec.harnessId),
                    models = models,
                    enabledHarnessIds = setOf(spec.harnessId)
                )
                val selectedIndex = models.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                val spinner = SettingsUi.styledSpinner(
                    activity,
                    models.map { modelLabel(it) },
                    selectedIndex,
                    tokens
                ).apply {
                    exposeToAccessibility(spec.viewId, "${spec.label} model")
                }
                addView(SettingsUi.labeledField(activity, spec.label, spinner, tokens, DesignTokens.Spacing.md))
                controls.add(DefaultModelControl(spec.harnessId, models, spinner))
            }
        }
        return visibleRows
    }

    private data class DefaultModelSpec(
        val harnessId: String,
        val label: String,
        val enabled: Boolean,
        val viewId: Int
    )

    private fun List<DefaultModelControl>.selectedModel(harnessId: String, fallback: String): String {
        val control = firstOrNull { it.harnessId == harnessId } ?: return fallback
        val spinner = control.spinner ?: return fallback
        return control.models.getOrNull(spinner.selectedItemPosition)?.id ?: fallback
    }

    private fun modelLabel(model: ChatModelOption): String {
        return model.label.takeIf { it.isNotBlank() }
            ?: model.modelId?.takeIf { it.isNotBlank() }
            ?: model.id
    }

    private fun keepCodexHomePrefix(input: EditText) {
        var correcting = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (correcting) return
                val text = editable?.toString().orEmpty()
                if (text.isBlank()) return
                if (text.startsWith("~/")) return
                correcting = true
                val normalized = CodexWorkspacePaths.display(text)
                val suffix = normalized.removePrefix("~").trimStart('/')
                val next = if (suffix.isBlank()) "~/" else "~/$suffix"
                input.setText(next)
                input.setSelection(next.length)
                correcting = false
            }
        })
        if (input.text.isNotBlank() && !input.text.toString().startsWith("~/")) {
            input.setText(CodexWorkspacePaths.display(input.text.toString()))
            input.setSelection(input.text.length)
        }
    }
}
