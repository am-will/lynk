package dev.androidagent.settings.screens

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import dev.androidagent.AgentConfig
import dev.androidagent.AgentConfigStore
import dev.androidagent.HostWorkspacePaths
import dev.androidagent.HostHarnessDescriptor
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

        val hostHarnessControls = AgentConfig.HOST_HARNESSES.map { descriptor ->
            HostHarnessControl(
                descriptor = descriptor,
                checkBox = SettingsUi.harnessCheckBox(
                    activity,
                    descriptor.label,
                    config.isModelHarnessEnabled(descriptor.id),
                    "Enable ${descriptor.label} harness",
                    tokens,
                    descriptor.checkboxViewId()
                )
            )
        }
        val local = SettingsUi.harnessCheckBox(activity, "Local model (experimental)", config.experimentalLocalModelsEnabled, "Enable local harness", tokens, R.id.openclaw_harness_local_litert_checkbox)

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Backends", "Disabled harnesses are hidden from the model picker.", tokens))
            hostHarnessControls.forEachIndexed { index, control ->
                addView(control.checkBox, SettingsUi.stackedParams(activity, if (index == 0) DesignTokens.Spacing.md else DesignTokens.Spacing.sm))
            }
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
            exposeToAccessibility(R.id.openclaw_local_model_path_field, "Local model path")
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
            addView(SettingsUi.sectionHeader(activity, "Local Models", "Import and tune the on-device model harness.", tokens))
            addView(SettingsUi.labeledField(activity, "Model file", localModelPathInput, tokens, DesignTokens.Spacing.md))
            addView(
                SettingsUi.actionButton(activity, "Import Local Model", SettingsButtonTone.Secondary, tokens) {
                    callbacks.onImportRequested(localModelPathInput)
                }.exposeToAccessibility(R.id.openclaw_local_model_import_button, "Import local model"),
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
                    openClawHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_OPENCLAW, config.openClawHarnessEnabled),
                    hermesHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_HERMES, config.hermesHarnessEnabled),
                    codexHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_CODEX, config.codexHarnessEnabled),
                    opencodeHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_OPENCODE, config.opencodeHarnessEnabled),
                    piHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_PI, config.piHarnessEnabled),
                    devinHarnessEnabled = hostHarnessControls.isChecked(AgentConfig.HARNESS_DEVIN, config.devinHarnessEnabled),
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
                    devinDefaultModel = defaultModelControls.selectedModel(
                        AgentConfig.HARNESS_DEVIN,
                        config.devinDefaultModel
                    ),
                    experimentalLocalModelsEnabled = local.isChecked,
                    localModelPath = localModelPathInput.text.toString().trim(),
                    localModelBackend = localBackends.getOrElse(localBackendSpinner.selectedItemPosition) { LocalModelBackend.Cpu },
                    localContextTokens = localContextInput.text.toString().toIntOrNull()?.coerceIn(512, 262_144)
                        ?: config.localContextTokens,
                    localDeveloperToolsEnabled = localDeveloperTools.isChecked,
                    workspacePaths = workspaceControls.associate { control ->
                        control.harnessId to workspaceControls.workspacePath(control.harnessId)
                    }
                )
            )
            callbacks.onSettingsChanged()
        }

        (hostHarnessControls.map { it.checkBox } + listOf(local, localDeveloperTools)).forEach { checkBox ->
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

    private data class HostHarnessControl(
        val descriptor: HostHarnessDescriptor,
        val checkBox: CheckBox
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
                HostWorkspacePaths.editorText(spec.path),
                tokens,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ).apply {
                keepHomePrefix(this)
            }
            addView(SettingsUi.card(activity, tokens).apply {
                addView(SettingsUi.sectionHeader(activity, spec.title, spec.description, tokens))
                addView(SettingsUi.labeledField(activity, "Workspace path", input, tokens, DesignTokens.Spacing.md))
                addView(SettingsUi.body(activity, "Leave blank for QuickChats. The ~/ prefix means your user folder.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            }, SettingsUi.stackedParams(activity))
            WorkspaceControl(spec.harnessId, input)
        }
    }

    private fun workspaceSpecs(config: AgentConfig): List<WorkspaceSpec> {
        return AgentConfig.HOST_HARNESSES
            .filter { it.supportsWorkspace }
            .map { descriptor ->
                WorkspaceSpec(
                    harnessId = descriptor.id,
                    title = "${descriptor.label} Default Workspace",
                    description = "New ${descriptor.label} chats start here when ${descriptor.label} is selected.",
                    path = config.workspacePathForHarness(descriptor.id)
                )
            }
    }

    private fun List<WorkspaceControl>.workspacePath(harnessId: String): String {
        return firstOrNull { it.harnessId == harnessId }
            ?.input
            ?.text
            ?.toString()
            ?.let(HostWorkspacePaths::normalizeInput)
            .orEmpty()
    }

    private fun LinearLayout.addDefaultModelRows(
        activity: Activity,
        tokens: ThemeTokens,
        config: AgentConfig,
        controls: MutableList<DefaultModelControl>
    ): Int {
        val snapshot = DiagnosticsBackendSnapshot.current()
        val specs = AgentConfig.HOST_HARNESSES.map { descriptor ->
            DefaultModelSpec(
                harnessId = descriptor.id,
                label = "${descriptor.label} default",
                enabled = config.isModelHarnessEnabled(descriptor.id),
                viewId = descriptor.defaultModelSpinnerViewId()
            )
        }
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

    private fun List<HostHarnessControl>.isChecked(harnessId: String, fallback: Boolean): Boolean {
        return firstOrNull { it.descriptor.id == harnessId }?.checkBox?.isChecked ?: fallback
    }

    private fun HostHarnessDescriptor.checkboxViewId(): Int {
        return when (id) {
            AgentConfig.HARNESS_OPENCLAW -> R.id.openclaw_harness_openclaw_checkbox
            AgentConfig.HARNESS_HERMES -> R.id.openclaw_harness_hermes_checkbox
            AgentConfig.HARNESS_CODEX -> R.id.openclaw_harness_codex_checkbox
            AgentConfig.HARNESS_OPENCODE -> R.id.openclaw_harness_opencode_checkbox
            AgentConfig.HARNESS_PI -> R.id.openclaw_harness_pi_checkbox
            AgentConfig.HARNESS_DEVIN -> R.id.openclaw_harness_devin_checkbox
            else -> View.NO_ID
        }
    }

    private fun HostHarnessDescriptor.defaultModelSpinnerViewId(): Int {
        return when (id) {
            AgentConfig.HARNESS_OPENCLAW -> R.id.openclaw_default_model_spinner
            AgentConfig.HARNESS_HERMES -> R.id.openclaw_hermes_default_model_spinner
            AgentConfig.HARNESS_CODEX -> R.id.openclaw_codex_default_model_spinner
            AgentConfig.HARNESS_OPENCODE -> R.id.openclaw_opencode_default_model_spinner
            AgentConfig.HARNESS_PI -> R.id.openclaw_pi_default_model_spinner
            AgentConfig.HARNESS_DEVIN -> R.id.openclaw_devin_default_model_spinner
            else -> View.NO_ID
        }
    }

    private fun keepHomePrefix(input: EditText) {
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
                val normalized = HostWorkspacePaths.display(text)
                val suffix = normalized.removePrefix("~").trimStart('/')
                val next = if (suffix.isBlank()) "~/" else "~/$suffix"
                input.setText(next)
                input.setSelection(next.length)
                correcting = false
            }
        })
        if (input.text.isNotBlank() && !input.text.toString().startsWith("~/")) {
            input.setText(HostWorkspacePaths.display(input.text.toString()))
            input.setSelection(input.text.length)
        }
    }
}
