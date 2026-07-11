package dev.androidagent.settings.screens

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import dev.androidagent.AgentConfigStore
import dev.androidagent.R
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object ConnectionSettingsScreen {

    interface Callbacks {
        fun onSettingsChanged()
        fun onBack()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val config = AgentConfigStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(SettingsUi.toolbar(activity, "Connection", tokens, callbacks::onBack))

        val hostInput = SettingsUi.configField(activity, "WebSocket URL", config.hostUrl, tokens).apply {
            exposeToAccessibility(R.id.openclaw_bridge_url_field, "Bridge WebSocket URL")
        }
        val deviceInput = SettingsUi.configField(activity, "Device ID", config.deviceId, tokens).apply {
            exposeToAccessibility(R.id.openclaw_device_id_field, "Device ID")
        }
        val tokenInput = SettingsUi.configField(
            activity,
            "Auth token",
            config.token,
            tokens,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            exposeToAccessibility(R.id.openclaw_bridge_token_field, "Bridge auth token")
        }

        fun saveCurrent() {
            AgentConfigStore.save(
                activity,
                AgentConfigStore.load(activity).copy(
                    hostUrl = hostInput.text.toString().trim(),
                    hostUrlCandidates = emptyList(),
                    allowInsecureTrustedOverlay = false,
                    deviceId = deviceInput.text.toString().trim(),
                    token = tokenInput.text.toString().trim()
                )
            )
            callbacks.onSettingsChanged()
        }
        SettingsUi.onTextChanged(hostInput) { saveCurrent() }
        SettingsUi.onTextChanged(deviceInput) { saveCurrent() }
        SettingsUi.onTextChanged(tokenInput) { saveCurrent() }

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Pairing", "Connect to the PC bridge over WebSocket.", tokens))
            addView(SettingsUi.labeledField(activity, "URL", hostInput, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.labeledField(activity, "Pairing ID", deviceInput, tokens))
            addView(SettingsUi.labeledField(activity, "Token", tokenInput, tokens))
        }, SettingsUi.stackedParams(activity))

        return root
    }
}
