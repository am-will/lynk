package dev.androidagent

import android.content.Context

data class AgentConfig(
    val hostUrl: String,
    val deviceId: String,
    val token: String,
    val openAiApiKey: String,
    val systemPrompt: String,
    val model: String,
    val reasoningEffort: String
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

    fun load(context: Context): AgentConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AgentConfig(
            hostUrl = prefs.getString(HOST_URL, "ws://127.0.0.1:8788/phone") ?: "ws://127.0.0.1:8788/phone",
            deviceId = prefs.getString(DEVICE_ID, "openclaw-agent") ?: "openclaw-agent",
            token = sanitizedToken(prefs.getString(TOKEN, "")),
            openAiApiKey = prefs.getString(OPENAI_API_KEY, "") ?: "",
            systemPrompt = prefs.getString(SYSTEM_PROMPT, DefaultSystemPrompt.text) ?: DefaultSystemPrompt.text,
            model = prefs.getString(MODEL, "gpt-5.5") ?: "gpt-5.5",
            reasoningEffort = prefs.getString(REASONING_EFFORT, "medium") ?: "medium"
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
