package dev.androidagent.settings.screens

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.androidagent.R
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsComponents.BadgeTone
import dev.androidagent.settings.SettingsComponents.StatusTone
import dev.androidagent.settings.SettingsDestination
import dev.androidagent.settings.SettingsStatusProvider
import dev.androidagent.settings.StatusLevel
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object SettingsHubScreen {

    interface Callbacks {
        fun navigate(destination: SettingsDestination)
        fun refreshStatus()
        fun bridgeConnected(): Boolean
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(tokens.background)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            exposeToAccessibility(viewId = R.id.openclaw_root, description = "Android Agent settings")
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SettingsComponents.dp(activity, DesignTokens.Spacing.lg),
                SettingsComponents.dp(activity, DesignTokens.Spacing.md),
                SettingsComponents.dp(activity, DesignTokens.Spacing.lg),
                SettingsComponents.dp(activity, DesignTokens.Spacing.xxxl)
            )
        }

        // Header with avatar, title, subtitle, menu
        root.addView(SettingsComponents.hubHeader(
            context = activity,
            tokens = tokens,
            titleText = "Android Agent",
            subtitleText = "Android bubble endpoint for your agents",
            onMenu = { /* future overflow */ }
        ))

        val snapshot = SettingsStatusProvider.snapshot(
            activity,
            bridgeConnected = callbacks.bridgeConnected()
        )
        root.addView(buildStatusChipRow(activity, tokens, snapshot), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        val categoriesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderCategories(activity, tokens, categoriesContainer, callbacks)
        root.addView(categoriesContainer, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        scroll.addView(root)
        return scroll
    }

    private fun buildStatusChipRow(activity: Activity, tokens: ThemeTokens, snapshot: dev.androidagent.settings.SettingsStatusSnapshot): View {
        val chips = listOf(
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_wifi, "Connected", "LAN", mapStatus(snapshot.connectedLan)),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_health, "Bridge", statusLabel(snapshot.bridgeHealthy), mapStatus(snapshot.bridgeHealthy)),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_lock, "Auth", if (snapshot.authOk == StatusLevel.Good) "OK" else "Off", mapStatus(snapshot.authOk)),
            SettingsComponents.statusChip(
                activity,
                tokens,
                R.drawable.ic_brand_circle,
                "Pet",
                if (snapshot.serviceRunning) "On" else "Off",
                if (snapshot.serviceRunning) StatusTone.Good else StatusTone.Idle
            )
        )
        return SettingsComponents.statusChipRow(activity, chips)
    }

    private fun mapStatus(level: StatusLevel): StatusTone = when (level) {
        StatusLevel.Good -> StatusTone.Good
        StatusLevel.Warning -> StatusTone.Warn
        StatusLevel.Bad -> StatusTone.Bad
        StatusLevel.Idle -> StatusTone.Idle
        StatusLevel.Active -> StatusTone.Good
    }

    private fun statusLabel(level: StatusLevel): String = when (level) {
        StatusLevel.Good -> "Healthy"
        StatusLevel.Warning -> "Warn"
        StatusLevel.Bad -> "Down"
        StatusLevel.Idle -> "Idle"
        StatusLevel.Active -> "Active"
    }

    private data class CategoryRowSpec(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val tone: BadgeTone,
        val destination: SettingsDestination
    )

    private fun renderCategories(activity: Activity, tokens: ThemeTokens, container: LinearLayout, callbacks: Callbacks) {
        container.removeAllViews()
        val specs = listOf(
            CategoryRowSpec("Runtime", "Host bridge, local model, backends", R.drawable.ic_terminal, BadgeTone.Teal, SettingsDestination.Runtime),
            CategoryRowSpec("Connection", "URL, pairing, network, transport", R.drawable.ic_link, BadgeTone.Blue, SettingsDestination.Connection),
            CategoryRowSpec("Voice", "Realtime voice, transcription, audio", R.drawable.ic_voice_idle, BadgeTone.Violet, SettingsDestination.Voice),
            CategoryRowSpec("Safety", "Confirmations, blocklists, guardrails", R.drawable.ic_shield, BadgeTone.Amber, SettingsDestination.Safety),
            CategoryRowSpec("Appearance", "Theme, bubble, font size", R.drawable.ic_palette, BadgeTone.Pink, SettingsDestination.Appearance)
        )
        specs.forEachIndexed { index, spec ->
            val row = SettingsComponents.categoryRow(
                context = activity,
                tokens = tokens,
                iconRes = spec.iconRes,
                tone = spec.tone,
                titleText = spec.title,
                subtitleText = spec.subtitle,
                onClick = { callbacks.navigate(spec.destination) }
            )
            container.addView(row, SettingsComponents.verticalMargin(
                activity,
                top = if (index == 0) 0 else DesignTokens.Spacing.sm + 2
            ))
        }
    }
}
