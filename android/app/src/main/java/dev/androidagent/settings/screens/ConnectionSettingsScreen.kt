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
        fun onSaved()
        fun toggleAgentService()
        fun isAgentServiceRunning(): Boolean
        fun openOverlaySettings()
        fun requestMicPermission()
        fun requestLocationPermission()
        fun openAccessibilitySettings()
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

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Bridge pairing", "Connect to the PC bridge over WebSocket.", tokens))
            addView(SettingsUi.labeledField(activity, "WebSocket URL", hostInput, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.labeledField(activity, "Device ID", deviceInput, tokens))
            addView(SettingsUi.labeledField(activity, "Auth token", tokenInput, tokens))
        }, SettingsUi.stackedParams(activity))

        val toggleLabel = if (callbacks.isAgentServiceRunning()) "Stop Agent Bubble" else "Start Agent Bubble"
        root.addView(
            SettingsUi.actionButton(
                activity,
                toggleLabel,
                if (callbacks.isAgentServiceRunning()) dev.androidagent.settings.SettingsButtonTone.Secondary else dev.androidagent.settings.SettingsButtonTone.Primary,
                tokens,
                callbacks::toggleAgentService
            ).exposeToAccessibility(R.id.openclaw_agent_toggle_button, "Agent bubble toggle"),
            SettingsUi.stackedParams(activity)
        )

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Permissions", "Required capabilities for the agent bubble.", tokens))
            addView(SettingsUi.actionButton(activity, "Grant Overlay Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::openOverlaySettings).apply {
                exposeToAccessibility(R.id.openclaw_overlay_permission_button, "Grant overlay permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.md))
            addView(SettingsUi.actionButton(activity, "Grant Microphone Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::requestMicPermission).apply {
                exposeToAccessibility(R.id.openclaw_microphone_permission_button, "Grant microphone permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2))
            addView(SettingsUi.actionButton(activity, "Grant Location Permission", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::requestLocationPermission).apply {
                exposeToAccessibility(R.id.openclaw_location_permission_button, "Grant location permission")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2))
            addView(SettingsUi.actionButton(activity, "Open Accessibility Settings", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens, callbacks::openAccessibilitySettings).apply {
                exposeToAccessibility(R.id.openclaw_accessibility_settings_button, "Open accessibility settings")
            }, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Save", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                val saved = config.copy(
                    hostUrl = hostInput.text.toString().trim(),
                    deviceId = deviceInput.text.toString().trim(),
                    token = tokenInput.text.toString().trim()
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
