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

data class AgentConfig(
    val hostUrl: String,
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
    val localDeveloperToolsEnabled: Boolean = false
)

object AgentConfigStore {
    private const val KNOWN_WEAK_DEFAULT_TOKEN = "12345678"
    private const val PREFS = "open_claw_agent_config"
    private const val HOST_URL = "host_url"
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
    private const val DEFAULT_LOCAL_CONTEXT_TOKENS = 4096

    fun load(context: Context): AgentConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val experimentalLocalModelsEnabled = prefs.getBoolean(EXPERIMENTAL_LOCAL_MODELS_ENABLED, false)
        return AgentConfig(
            hostUrl = prefs.getString(HOST_URL, "ws://127.0.0.1:8788/phone") ?: "ws://127.0.0.1:8788/phone",
            deviceId = prefs.getString(DEVICE_ID, "openclaw-agent") ?: "openclaw-agent",
            token = sanitizedToken(prefs.getString(TOKEN, "")),
            openAiApiKey = prefs.getString(OPENAI_API_KEY, "") ?: "",
            systemPrompt = prefs.getString(SYSTEM_PROMPT, DefaultSystemPrompt.text) ?: DefaultSystemPrompt.text,
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
            localDeveloperToolsEnabled = prefs.getBoolean(LOCAL_DEVELOPER_TOOLS_ENABLED, false)
        )
    }

    fun save(context: Context, config: AgentConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST_URL, config.hostUrl)
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
