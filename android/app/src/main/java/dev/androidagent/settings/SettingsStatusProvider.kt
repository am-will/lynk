package dev.androidagent.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.core.content.ContextCompat
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentForegroundService
import dev.androidagent.AgentLocationProvider
import dev.androidagent.accessibility.PhoneAccessibilityService
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.ui.DesignTokens

data class SettingsStatusSnapshot(
    val connectedLan: StatusLevel,
    val bridgeHealthy: StatusLevel,
    val authOk: StatusLevel,
    val voiceIdle: StatusLevel,
    val overlayGranted: Boolean,
    val microphoneGranted: Boolean,
    val locationGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val serviceRunning: Boolean,
    val endpointSummary: String,
    val setupMessage: String
)

enum class StatusLevel(val label: String) {
    Good("OK"),
    Warning("Warn"),
    Bad("Off"),
    Idle("Idle"),
    Active("Active")
}

object SettingsStatusProvider {

    fun snapshot(context: Context, bridgeConnected: Boolean = false, voiceActive: Boolean = false): SettingsStatusSnapshot {
        val config = AgentConfigStore.load(context)
        val tokens = DesignTokens.resolve(context)
        val overlay = Settings.canDrawOverlays(context)
        val microphone = hasMicPermission(context)
        val location = AgentLocationProvider.hasLocationPermission(context)
        val accessibility = isAccessibilityEnabled(context)
        val service = AgentForegroundService.isRunning
        val bridgeTokenReady = config.token.isNotBlank()
        val localModelReady = LocalModelStore.exists(config.localModelPath)
        val enabledHarnesses = listOfNotNull(
            "OpenClaw".takeIf { config.openClawHarnessEnabled },
            "Hermes".takeIf { config.hermesHarnessEnabled },
            "Codex".takeIf { config.codexHarnessEnabled },
            "Local".takeIf { config.experimentalLocalModelsEnabled }
        ).ifEmpty { listOf("none") }.joinToString(", ")
        val localLine = when {
            !config.experimentalLocalModelsEnabled -> "Local LiteRT-LM: off"
            localModelReady -> "Local LiteRT-LLM: ready (${config.localModelBackend.label})"
            else -> "Local LiteRT-LLM: enabled, model missing"
        }

        val endpointSummary = """
            ${config.deviceId} -> ${config.hostUrl}
            Auth token: ${if (bridgeTokenReady) "saved" else "missing"}
            Harnesses: $enabledHarnesses
            $localLine
        """.trimIndent()

        val setupMessage = when {
            !bridgeTokenReady -> "Paste the PC PHONE_AGENT_TOKEN in Connection before starting the bridge session."
            overlay && microphone && accessibility -> {
                if (config.experimentalLocalModelsEnabled && !localModelReady) {
                    "Ready for host models. Import a LiteRT-LM .litertlm model before Local appears in the picker."
                } else {
                    "Ready. Start the bubble when your bridge is listening."
                }
            }
            else -> "Finish the missing permission steps before expecting reliable automation."
        }

        return SettingsStatusSnapshot(
            connectedLan = if (bridgeConnected) StatusLevel.Good else if (service) StatusLevel.Warning else StatusLevel.Bad,
            bridgeHealthy = if (bridgeConnected) StatusLevel.Good else StatusLevel.Warning,
            authOk = if (bridgeTokenReady) StatusLevel.Good else StatusLevel.Bad,
            voiceIdle = if (voiceActive) StatusLevel.Active else StatusLevel.Idle,
            overlayGranted = overlay,
            microphoneGranted = microphone,
            locationGranted = location,
            accessibilityEnabled = accessibility,
            serviceRunning = service,
            endpointSummary = endpointSummary,
            setupMessage = setupMessage
        )
    }

    fun statusColor(context: Context, level: StatusLevel): Int {
        val tokens = DesignTokens.resolve(context)
        return when (level) {
            StatusLevel.Good -> tokens.success
            StatusLevel.Warning -> tokens.warning
            StatusLevel.Bad -> tokens.danger
            StatusLevel.Idle -> tokens.secondaryText
            StatusLevel.Active -> tokens.accent
        }
    }

    private fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PhoneAccessibilityService::class.java)
        val enabledSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val enabledBySecureSetting = enabledSetting.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened)?.let { component ->
                component.packageName == expected.packageName && component.className == expected.className
            } == true
        }
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledByManager = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val serviceInfo = service.resolveInfo.serviceInfo
                serviceInfo.packageName == expected.packageName && serviceInfo.name == expected.className
            }
        return enabledBySecureSetting || enabledByManager
    }
}
