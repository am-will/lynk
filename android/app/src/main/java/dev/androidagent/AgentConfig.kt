package dev.androidagent

import android.content.Context

enum class AgentMode(val key: String, val label: String) {
    Host("host", "Host bridge"),
    Local("local", "Local phone");

    companion object {
        fun fromKey(value: String?): AgentMode =
            values().firstOrNull { it.key == value } ?: Host
    }
}

enum class LocalModelBackend(val key: String, val label: String) {
    Cpu("cpu", "CPU"),
    Gpu("gpu", "GPU"),
    Npu("npu", "NPU");

    companion object {
        fun fromKey(value: String?): LocalModelBackend =
            values().firstOrNull { it.key == value } ?: Cpu
    }
}

enum class ChatActiveSendMode(val key: String, val label: String) {
    Queue("queue", "Queue"),
    Steer("steer", "Steer");

    companion object {
        fun fromKey(value: String?): ChatActiveSendMode =
            values().firstOrNull { it.key == value } ?: Steer
    }
}

data class AgentConfig(
    val hostUrl: String,
    val hostUrlCandidates: List<String> = emptyList(),
    val deviceId: String,
    val token: String,
    val openAiApiKey: String,
    val systemPrompt: String,
    val model: String,
    val reasoningEffort: String,
    val agentMode: AgentMode = AgentMode.Host,
    val experimentalLocalModelsEnabled: Boolean = false,
    val localModelPath: String = "",
    val localModelBackend: LocalModelBackend = LocalModelBackend.Cpu,
    val localContextTokens: Int = 4096,
    val localDeveloperToolsEnabled: Boolean = false,
    val openClawHarnessEnabled: Boolean = true,
    val hermesHarnessEnabled: Boolean = true,
    val codexHarnessEnabled: Boolean = true,
    val opencodeHarnessEnabled: Boolean = true,
    val piHarnessEnabled: Boolean = true,
    val openClawDefaultModel: String = "",
    val hermesDefaultModel: String = "",
    val codexDefaultModel: String = "",
    val opencodeDefaultModel: String = "",
    val piDefaultModel: String = "",
    val codexWorkspacePath: String = "",
    val opencodeWorkspacePath: String = "",
    val piWorkspacePath: String = "",
    val activeSendMode: ChatActiveSendMode = ChatActiveSendMode.Steer,
    val petEnabled: Boolean = true
) {
    fun enabledModelHarnessIds(): Set<String> {
        val ids = mutableSetOf<String>()
        if (openClawHarnessEnabled) ids.add(HARNESS_OPENCLAW)
        if (hermesHarnessEnabled) ids.add(HARNESS_HERMES)
        if (codexHarnessEnabled) ids.add(HARNESS_CODEX)
        if (opencodeHarnessEnabled) ids.add(HARNESS_OPENCODE)
        if (piHarnessEnabled) ids.add(HARNESS_PI)
        if (experimentalLocalModelsEnabled) ids.add(HARNESS_LOCAL)
        return ids
    }

    fun isModelHarnessEnabled(harnessId: String?): Boolean {
        return when (harnessId?.lowercase()) {
            HARNESS_OPENCLAW -> openClawHarnessEnabled
            HARNESS_HERMES -> hermesHarnessEnabled
            HARNESS_CODEX -> codexHarnessEnabled
            HARNESS_OPENCODE -> opencodeHarnessEnabled
            HARNESS_PI -> piHarnessEnabled
            HARNESS_LOCAL -> experimentalLocalModelsEnabled
            else -> true
        }
    }

    fun defaultModelForHarness(harnessId: String?): String? {
        return when (harnessId?.lowercase()) {
            HARNESS_OPENCLAW -> openClawDefaultModel
            HARNESS_HERMES -> hermesDefaultModel
            HARNESS_CODEX -> codexDefaultModel
            HARNESS_OPENCODE -> opencodeDefaultModel
            HARNESS_PI -> piDefaultModel
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val HARNESS_OPENCLAW = "openclaw"
        const val HARNESS_HERMES = "hermes"
        const val HARNESS_CODEX = "codex"
        const val HARNESS_OPENCODE = "opencode"
        const val HARNESS_PI = "pi"
        const val HARNESS_LOCAL = "local"

        fun isWorkspaceHarness(harnessId: String?): Boolean {
            return when (harnessId?.lowercase()) {
                HARNESS_CODEX,
                HARNESS_OPENCODE,
                HARNESS_PI -> true
                else -> false
            }
        }
    }
}

object AgentConfigStore {
    private const val KNOWN_WEAK_DEFAULT_TOKEN = "12345678"
    private const val PREFS = "open_claw_agent_config"
    private const val HOST_URL = "host_url"
    private const val HOST_URL_CANDIDATES = "host_url_candidates"
    private const val DEVICE_ID = "device_id"
    private const val TOKEN = "token"
    private const val OPENAI_API_KEY = "openai_api_key"
    private const val SYSTEM_PROMPT = "system_prompt"
    private const val MODEL = "model"
    private const val REASONING_EFFORT = "reasoning_effort"
    private const val AGENT_MODE = "agent_mode"
    private const val EXPERIMENTAL_LOCAL_MODELS_ENABLED = "experimental_local_models_enabled"
    private const val LOCAL_MODEL_PATH = "local_model_path"
    private const val LOCAL_MODEL_BACKEND = "local_model_backend"
    private const val LOCAL_CONTEXT_TOKENS = "local_context_tokens"
    private const val LOCAL_DEVELOPER_TOOLS_ENABLED = "local_developer_tools_enabled"
    private const val OPENCLAW_HARNESS_ENABLED = "openclaw_harness_enabled"
    private const val HERMES_HARNESS_ENABLED = "hermes_harness_enabled"
    private const val CODEX_HARNESS_ENABLED = "codex_harness_enabled"
    private const val OPENCODE_HARNESS_ENABLED = "opencode_harness_enabled"
    private const val PI_HARNESS_ENABLED = "pi_harness_enabled"
    private const val OPENCLAW_DEFAULT_MODEL = "openclaw_default_model"
    private const val HERMES_DEFAULT_MODEL = "hermes_default_model"
    private const val CODEX_DEFAULT_MODEL = "codex_default_model"
    private const val OPENCODE_DEFAULT_MODEL = "opencode_default_model"
    private const val PI_DEFAULT_MODEL = "pi_default_model"
    private const val CODEX_WORKSPACE_PATH = "codex_workspace_path"
    private const val OPENCODE_WORKSPACE_PATH = "opencode_workspace_path"
    private const val PI_WORKSPACE_PATH = "pi_workspace_path"
    private const val ACTIVE_SEND_MODE = "active_send_mode"
    private const val PET_ENABLED = "pet_enabled"
    private const val DEFAULT_LOCAL_CONTEXT_TOKENS = 4096

    fun load(context: Context): AgentConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val experimentalLocalModelsEnabled = prefs.getBoolean(EXPERIMENTAL_LOCAL_MODELS_ENABLED, false)
        return AgentConfig(
            hostUrl = prefs.getString(HOST_URL, "ws://127.0.0.1:8788/phone") ?: "ws://127.0.0.1:8788/phone",
            hostUrlCandidates = prefs.getString(HOST_URL_CANDIDATES, "")
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            deviceId = prefs.getString(DEVICE_ID, "openclaw-agent") ?: "openclaw-agent",
            token = sanitizedToken(prefs.getString(TOKEN, "")),
            openAiApiKey = prefs.getString(OPENAI_API_KEY, "") ?: "",
            systemPrompt = prefs.getString(SYSTEM_PROMPT, "") ?: "",
            model = prefs.getString(MODEL, "gpt-5.5") ?: "gpt-5.5",
            reasoningEffort = prefs.getString(REASONING_EFFORT, "medium") ?: "medium",
            agentMode = if (experimentalLocalModelsEnabled) {
                AgentMode.fromKey(prefs.getString(AGENT_MODE, AgentMode.Host.key))
            } else {
                AgentMode.Host
            },
            experimentalLocalModelsEnabled = experimentalLocalModelsEnabled,
            localModelPath = prefs.getString(LOCAL_MODEL_PATH, "") ?: "",
            localModelBackend = LocalModelBackend.fromKey(prefs.getString(LOCAL_MODEL_BACKEND, LocalModelBackend.Cpu.key)),
            localContextTokens = prefs.getInt(LOCAL_CONTEXT_TOKENS, DEFAULT_LOCAL_CONTEXT_TOKENS).coerceIn(512, 131_072),
            localDeveloperToolsEnabled = prefs.getBoolean(LOCAL_DEVELOPER_TOOLS_ENABLED, false),
            openClawHarnessEnabled = prefs.getBoolean(OPENCLAW_HARNESS_ENABLED, true),
            hermesHarnessEnabled = prefs.getBoolean(HERMES_HARNESS_ENABLED, true),
            codexHarnessEnabled = prefs.getBoolean(CODEX_HARNESS_ENABLED, true),
            opencodeHarnessEnabled = prefs.getBoolean(OPENCODE_HARNESS_ENABLED, true),
            piHarnessEnabled = prefs.getBoolean(PI_HARNESS_ENABLED, true),
            openClawDefaultModel = prefs.getString(OPENCLAW_DEFAULT_MODEL, "") ?: "",
            hermesDefaultModel = prefs.getString(HERMES_DEFAULT_MODEL, "") ?: "",
            codexDefaultModel = prefs.getString(CODEX_DEFAULT_MODEL, "") ?: "",
            opencodeDefaultModel = prefs.getString(OPENCODE_DEFAULT_MODEL, "") ?: "",
            piDefaultModel = prefs.getString(PI_DEFAULT_MODEL, "") ?: "",
            codexWorkspacePath = prefs.getString(CODEX_WORKSPACE_PATH, "") ?: "",
            opencodeWorkspacePath = prefs.getString(OPENCODE_WORKSPACE_PATH, "") ?: "",
            piWorkspacePath = prefs.getString(PI_WORKSPACE_PATH, "") ?: "",
            activeSendMode = ChatActiveSendMode.fromKey(prefs.getString(ACTIVE_SEND_MODE, ChatActiveSendMode.Steer.key)),
            petEnabled = prefs.getBoolean(PET_ENABLED, true)
        )
    }

    fun save(context: Context, config: AgentConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST_URL, config.hostUrl)
            .putString(HOST_URL_CANDIDATES, config.hostUrlCandidates.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            .putString(DEVICE_ID, config.deviceId)
            .putString(TOKEN, config.token)
            .putString(OPENAI_API_KEY, config.openAiApiKey)
            .putString(SYSTEM_PROMPT, config.systemPrompt)
            .putString(MODEL, config.model)
            .putString(REASONING_EFFORT, config.reasoningEffort)
            .putString(AGENT_MODE, config.agentMode.key)
            .putBoolean(EXPERIMENTAL_LOCAL_MODELS_ENABLED, config.experimentalLocalModelsEnabled)
            .putString(LOCAL_MODEL_PATH, config.localModelPath)
            .putString(LOCAL_MODEL_BACKEND, config.localModelBackend.key)
            .putInt(LOCAL_CONTEXT_TOKENS, config.localContextTokens.coerceIn(512, 131_072))
            .putBoolean(LOCAL_DEVELOPER_TOOLS_ENABLED, config.localDeveloperToolsEnabled)
            .putBoolean(OPENCLAW_HARNESS_ENABLED, config.openClawHarnessEnabled)
            .putBoolean(HERMES_HARNESS_ENABLED, config.hermesHarnessEnabled)
            .putBoolean(CODEX_HARNESS_ENABLED, config.codexHarnessEnabled)
            .putBoolean(OPENCODE_HARNESS_ENABLED, config.opencodeHarnessEnabled)
            .putBoolean(PI_HARNESS_ENABLED, config.piHarnessEnabled)
            .putString(OPENCLAW_DEFAULT_MODEL, config.openClawDefaultModel.trim())
            .putString(HERMES_DEFAULT_MODEL, config.hermesDefaultModel.trim())
            .putString(CODEX_DEFAULT_MODEL, config.codexDefaultModel.trim())
            .putString(OPENCODE_DEFAULT_MODEL, config.opencodeDefaultModel.trim())
            .putString(PI_DEFAULT_MODEL, config.piDefaultModel.trim())
            .putString(CODEX_WORKSPACE_PATH, config.codexWorkspacePath.trim())
            .putString(OPENCODE_WORKSPACE_PATH, config.opencodeWorkspacePath.trim())
            .putString(PI_WORKSPACE_PATH, config.piWorkspacePath.trim())
            .putString(ACTIVE_SEND_MODE, config.activeSendMode.key)
            .putBoolean(PET_ENABLED, config.petEnabled)
            .apply()
    }

    private fun sanitizedToken(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return trimmed.takeUnless { it == KNOWN_WEAK_DEFAULT_TOKEN }.orEmpty()
    }
}

enum class PanelAnimationStyle(val key: String) {
    Circular("circular"),
    Slide("slide");

    companion object {
        fun fromKey(value: String?): PanelAnimationStyle =
            values().firstOrNull { it.key == value } ?: Circular
    }
}

data class AppearancePrefs(
    val panelAnimation: PanelAnimationStyle,
    val bubbleSizeDp: Int = DEFAULT_BUBBLE_SIZE_DP
) {
    companion object {
        const val MIN_BUBBLE_SIZE_DP = 40
        const val DEFAULT_BUBBLE_SIZE_DP = 88
        const val MAX_BUBBLE_SIZE_DP = 132
    }
}

object AppearancePrefsStore {
    private const val PREFS = "open_claw_agent_appearance"
    private const val PANEL_ANIMATION = "appearance_panel_animation"
    private const val BUBBLE_SIZE_DP = "appearance_bubble_size_dp"

    fun load(context: Context): AppearancePrefs {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppearancePrefs(
            panelAnimation = PanelAnimationStyle.fromKey(prefs.getString(PANEL_ANIMATION, PanelAnimationStyle.Circular.key)),
            bubbleSizeDp = prefs.getInt(BUBBLE_SIZE_DP, AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP)
        )
    }

    fun save(context: Context, prefs: AppearancePrefs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PANEL_ANIMATION, prefs.panelAnimation.key)
            .putInt(BUBBLE_SIZE_DP, prefs.bubbleSizeDp)
            .apply()
    }

    fun setPanelAnimation(context: Context, style: PanelAnimationStyle) {
        save(context, load(context).copy(panelAnimation = style))
    }

    fun setBubbleSize(context: Context, dp: Int) {
        save(context, load(context).copy(bubbleSizeDp = dp))
    }
}
