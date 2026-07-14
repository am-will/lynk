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
        fun onSettingsChanged()
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
        SettingsUi.onTextChanged(openAiKeyInput) {
            AgentConfigStore.save(
                activity,
                AgentConfigStore.load(activity).copy(openAiApiKey = openAiKeyInput.text.toString().trim())
            )
            callbacks.onSettingsChanged()
        }

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Realtime voice", "Uses OpenAI Realtime via the PC bridge.", tokens))
            addView(SettingsUi.labeledField(activity, "OpenAI API key", openAiKeyInput, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "Prefer PC OPENAI_API_KEY for bridge voice. An Android key is sent to the bridge only over wss and is also used directly over HTTPS for phone transcription. Voice uses the selected chat backend for delegated work, including the local on-device model.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
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

        return root
    }
}
