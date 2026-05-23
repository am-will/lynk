package dev.androidagent.settings

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.androidagent.settings.screens.ActivityDiagnosticsScreen
import dev.androidagent.settings.screens.AppearanceSettingsScreen
import dev.androidagent.settings.screens.ConnectionSettingsScreen
import dev.androidagent.AppShellActivity
import dev.androidagent.MainActivity
import dev.androidagent.settings.screens.LocalModelSettingsScreen
import dev.androidagent.settings.screens.RuntimeSettingsScreen
import dev.androidagent.settings.screens.SafetySettingsScreen
import dev.androidagent.settings.screens.SettingsHubScreen
import dev.androidagent.settings.screens.VoiceSettingsScreen
import dev.androidagent.ui.DesignTokens

class SettingsHost(
    private val activity: Activity,
    private val container: FrameLayout,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun ensureAgentServiceRunning()
        fun refreshStatus()
        fun onRunTargetChanged()
        fun requestMicPermission()
        fun requestLocationPermission()
        fun openAccessibilitySettings()
        fun openOverlaySettings()
        fun toggleAgentService()
        fun isAgentServiceRunning(): Boolean
        fun bridgeConnected(): Boolean
        fun voiceActive(): Boolean
    }

    private val tokens get() = SettingsUi.tokens(activity)

    fun showHub() {
        container.removeAllViews()
        container.addView(
            SettingsHubScreen.build(activity, tokens, object : SettingsHubScreen.Callbacks {
                override fun navigate(destination: SettingsDestination) = showScreen(destination)
                override fun onRunTargetChanged() = callbacks.onRunTargetChanged()
                override fun refreshStatus() = callbacks.refreshStatus()
                override fun bridgeConnected() = callbacks.bridgeConnected()
                override fun voiceActive() = callbacks.voiceActive()
            })
        )
    }

    fun navigateTo(destination: SettingsDestination) {
        showScreen(destination)
    }

    private fun showScreen(destination: SettingsDestination) {
        container.removeAllViews()
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(tokens.background)
            isFillViewport = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.xxxl)
            )
        }

        val back = { showHub() }
        val screenView: View = when (destination) {
            SettingsDestination.Runtime -> RuntimeSettingsScreen.build(activity, tokens, back)
            SettingsDestination.Connection -> ConnectionSettingsScreen.build(activity, tokens, object : ConnectionSettingsScreen.Callbacks {
                override fun onSaved() {
                    callbacks.refreshStatus()
                    DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Connection settings saved")
                }
                override fun toggleAgentService() = callbacks.toggleAgentService()
                override fun isAgentServiceRunning() = callbacks.isAgentServiceRunning()
                override fun openOverlaySettings() = callbacks.openOverlaySettings()
                override fun requestMicPermission() = callbacks.requestMicPermission()
                override fun requestLocationPermission() = callbacks.requestLocationPermission()
                override fun openAccessibilitySettings() = callbacks.openAccessibilitySettings()
                override fun onBack() = back()
            })
            SettingsDestination.Voice -> VoiceSettingsScreen.build(activity, tokens, object : VoiceSettingsScreen.Callbacks {
                override fun onSaved() {
                    callbacks.refreshStatus()
                    DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Voice settings saved")
                }
                override fun requestMicPermission() = callbacks.requestMicPermission()
                override fun startVoice() = callbacks.ensureAgentServiceRunning()
                override fun onBack() = back()
            })
            SettingsDestination.Safety -> SafetySettingsScreen.build(activity, tokens, back)
            SettingsDestination.Appearance -> AppearanceSettingsScreen.build(activity, tokens, object : AppearanceSettingsScreen.Callbacks {
                override fun onSaved() = callbacks.refreshStatus()
                override fun notifyAvatarChanged() {
                    val intent = Intent(activity, dev.androidagent.AgentForegroundService::class.java)
                        .setAction(dev.androidagent.AgentForegroundService.ACTION_REFRESH_AVATAR)
                    runCatching { androidx.core.content.ContextCompat.startForegroundService(activity, intent) }
                }
                override fun notifyBubbleResize(sizeDp: Int) {
                    val intent = Intent(activity, dev.androidagent.AgentForegroundService::class.java)
                        .setAction(dev.androidagent.AgentForegroundService.ACTION_RESIZE_BUBBLE)
                        .putExtra(dev.androidagent.AgentForegroundService.EXTRA_BUBBLE_SIZE_DP, sizeDp)
                    runCatching { androidx.core.content.ContextCompat.startForegroundService(activity, intent) }
                }
                override fun onBack() = back()
            })
            SettingsDestination.LocalModel -> {
                val importFieldHolder = arrayOf<android.widget.EditText?>(null)
                LocalModelSettingsScreen.build(activity, tokens, object : LocalModelSettingsScreen.Callbacks {
                    override fun onSaved() {
                        callbacks.refreshStatus()
                        DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Local model settings saved")
                    }
                    override fun onBack() = back()
                    override fun onImportRequested(pathField: android.widget.EditText) {
                        importFieldHolder[0] = pathField
                        (activity as? AppShellActivity)?.registerLocalModelImport(pathField)
                            ?: (activity as? MainActivity)?.registerLocalModelImport(pathField)
                    }
                })
            }
        }

        content.addView(screenView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(content)
        container.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    companion object {
        fun buildDiagnostics(activity: Activity): View {
            return ActivityDiagnosticsScreen.build(activity, SettingsUi.tokens(activity))
        }
    }
}
