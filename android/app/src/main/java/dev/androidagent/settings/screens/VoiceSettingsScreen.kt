package dev.androidagent.settings.screens

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentForegroundService
import dev.androidagent.R
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object VoiceSettingsScreen {

    interface Callbacks {
        fun onSaved()
        fun requestMicPermission()
        fun startVoice()
        fun onBack()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val config = AgentConfigStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(SettingsUi.toolbar(activity, "Voice", tokens, callbacks::onBack))

        val openAiKeyInput = SettingsUi.configField(
            activity,
            "OpenAI API key for realtime voice",
            config.openAiApiKey,
            tokens,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            exposeToAccessibility(R.id.openclaw_openai_api_key_field, "OpenAI API key for realtime voice")
        }

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Realtime voice", "Uses OpenAI Realtime via the PC bridge.", tokens))
            addView(SettingsUi.labeledField(activity, "OpenAI API key", openAiKeyInput, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "Save a key from Android settings or PC OPENAI_API_KEY. Voice uses the selected chat backend for delegated work, including Local LiteRT-LM.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Grant Microphone Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::requestMicPermission),
            SettingsUi.stackedParams(activity)
        )
        root.addView(
            SettingsUi.actionButton(activity, "Start Voice Session", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                callbacks.startVoice()
                val intent = Intent(activity, AgentForegroundService::class.java)
                    .setAction(AgentForegroundService.ACTION_START_VOICE)
                runCatching { ContextCompat.startForegroundService(activity, intent) }
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2)
        )

        root.addView(
            SettingsUi.actionButton(activity, "Save", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens) {
                AgentConfigStore.save(activity, config.copy(openAiApiKey = openAiKeyInput.text.toString().trim()))
                callbacks.onSaved()
                callbacks.onBack()
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.xl)
        )

        return root
    }
}
