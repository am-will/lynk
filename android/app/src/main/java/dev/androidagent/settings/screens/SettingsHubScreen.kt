package dev.androidagent.settings.screens

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
        fun togglePetEnabled()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val metrics = hubLayoutMetrics(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(tokens.background)
            setPadding(
                SettingsComponents.dp(activity, metrics.horizontalPaddingDp),
                SettingsComponents.dp(activity, metrics.verticalPaddingDp),
                SettingsComponents.dp(activity, metrics.horizontalPaddingDp),
                SettingsComponents.dp(activity, metrics.verticalPaddingDp)
            )
            exposeToAccessibility(viewId = R.id.openclaw_root, description = "Android Agent settings")
        }

        root.addView(SettingsComponents.hubHeader(
            context = activity,
            tokens = tokens,
            titleText = "Android Agent",
            subtitleText = "The best interface for your agents.",
            avatarSizeDp = metrics.headerAvatarSizeDp
        ))

        val snapshot = SettingsStatusProvider.snapshot(
            activity,
            bridgeConnected = callbacks.bridgeConnected()
        )
        root.addView(
            buildStatusChipGrid(activity, tokens, snapshot, metrics, callbacks),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg)
        )

        val categoriesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        renderCategories(activity, tokens, categoriesContainer, callbacks, metrics)
        root.addView(
            categoriesContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = SettingsComponents.dp(activity, DesignTokens.Spacing.xxl)
            }
        )

        return root
    }

    private fun buildStatusChipGrid(
        activity: Activity,
        tokens: ThemeTokens,
        snapshot: dev.androidagent.settings.SettingsStatusSnapshot,
        metrics: HubLayoutMetrics,
        callbacks: Callbacks
    ): View {
        val gridHeight = SettingsComponents.dp(activity, metrics.statusGridHeightDp)
        val chips = listOf(
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_link, "Link", linkValue(snapshot.connectedLan), mapStatus(snapshot.connectedLan), fillCell = true, iconSizeDp = metrics.statusChipIconSizeDp),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_health, "Bridge", statusLabel(snapshot.bridgeHealthy), mapStatus(snapshot.bridgeHealthy), fillCell = true, iconSizeDp = metrics.statusChipIconSizeDp),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_lock, "Auth", if (snapshot.authOk == StatusLevel.Good) "OK" else "Off", mapStatus(snapshot.authOk), fillCell = true, iconSizeDp = metrics.statusChipIconSizeDp),
            SettingsComponents.statusChip(
                activity,
                tokens,
                R.drawable.ic_dog,
                "Pet",
                if (snapshot.petEnabled) "On" else "Off",
                if (snapshot.petEnabled) StatusTone.Good else StatusTone.Idle,
                fillCell = true,
                iconSizeDp = metrics.statusChipIconSizeDp,
                highlighted = snapshot.petEnabled,
                onClick = callbacks::togglePetEnabled
            )
        )
        return SettingsComponents.statusChipGrid(activity, chips, gridHeight)
    }

    private fun linkValue(level: StatusLevel): String = when (level) {
        StatusLevel.Good -> "LAN"
        StatusLevel.Warning -> "Wait"
        StatusLevel.Bad -> "Off"
        StatusLevel.Idle -> "Idle"
        StatusLevel.Active -> "Active"
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

    private fun renderCategories(
        activity: Activity,
        tokens: ThemeTokens,
        container: LinearLayout,
        callbacks: Callbacks,
        metrics: HubLayoutMetrics
    ) {
        container.removeAllViews()
        val specs = listOf(
            CategoryRowSpec("Harness", "Backends, local models", R.drawable.ic_terminal, BadgeTone.Teal, SettingsDestination.Runtime),
            CategoryRowSpec("Connection", "URL, token, pairing", R.drawable.ic_link, BadgeTone.Blue, SettingsDestination.Connection),
            CategoryRowSpec("Voice", "API keys, transcription, models", R.drawable.ic_voice_idle, BadgeTone.Violet, SettingsDestination.Voice),
            CategoryRowSpec("System", "System prompt, permissions, phone control", R.drawable.ic_shield, BadgeTone.Amber, SettingsDestination.Safety),
            CategoryRowSpec("Appearance", "Theme, pet, size", R.drawable.ic_palette, BadgeTone.Pink, SettingsDestination.Appearance)
        )
        specs.forEachIndexed { index, spec ->
            val row = SettingsComponents.categoryRow(
                context = activity,
                tokens = tokens,
                iconRes = spec.iconRes,
                tone = spec.tone,
                titleText = spec.title,
                subtitleText = spec.subtitle,
                iconSizeDp = metrics.categoryIconSizeDp,
                fillRow = true,
                onClick = { callbacks.navigate(spec.destination) }
            )
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = if (index == 0) 0 else SettingsComponents.dp(activity, DesignTokens.Spacing.md)
                }
            )
        }
    }
}
