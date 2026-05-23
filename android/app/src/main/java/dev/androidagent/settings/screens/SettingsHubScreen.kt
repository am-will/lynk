package dev.androidagent.settings.screens

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentMode
import dev.androidagent.AgentModelOptions
import dev.androidagent.R
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsComponents.BadgeTone
import dev.androidagent.settings.SettingsComponents.StatusTone
import dev.androidagent.settings.SettingsDestination
import dev.androidagent.settings.SettingsSearchController
import dev.androidagent.settings.SettingsSearchEntry
import dev.androidagent.settings.SettingsStatusProvider
import dev.androidagent.settings.StatusLevel
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility

object SettingsHubScreen {

    interface Callbacks {
        fun navigate(destination: SettingsDestination)
        fun onRunTargetChanged()
        fun refreshStatus()
        fun bridgeConnected(): Boolean
        fun voiceActive(): Boolean
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

        root.addView(SettingsComponents.hubHeader(
            context = activity,
            tokens = tokens,
            titleText = "Android Agent",
            subtitleText = "Android bubble endpoint for your agents",
            onMenu = { /* future overflow */ }
        ))

        val runLabel = if (AgentConfigStore.load(activity).agentMode == AgentMode.Local) "Local phone" else "Host bridge"
        root.addView(SettingsComponents.runTargetPill(
            context = activity,
            tokens = tokens,
            currentLabel = runLabel,
            onClick = { showRunTargetMenu(activity, callbacks) }
        ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        val snapshot = SettingsStatusProvider.snapshot(
            activity,
            bridgeConnected = callbacks.bridgeConnected(),
            voiceActive = callbacks.voiceActive()
        )
        root.addView(buildStatusChipRow(activity, tokens, snapshot), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        val searchInput = SettingsComponents.searchBar(activity, tokens, "Type a setting or command")
        root.addView(searchInput, SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.md))

        root.addView(SettingsComponents.sectionHeader(
            context = activity,
            tokens = tokens,
            titleText = "Pinned",
            trailingText = "Edit",
            onTrailing = { /* future */ }
        ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        val searchResults = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val categoriesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderCategories(activity, tokens, categoriesContainer, callbacks)

        root.addView(searchResults)
        root.addView(categoriesContainer)

        val realInput = searchInput.findViewWithTag<EditText>("search_input")
        realInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (query.isBlank()) {
                    searchResults.visibility = View.GONE
                    categoriesContainer.visibility = View.VISIBLE
                } else {
                    searchResults.visibility = View.VISIBLE
                    categoriesContainer.visibility = View.GONE
                    renderSearchResults(activity, tokens, searchResults, query, callbacks)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildStatusChipRow(activity: Activity, tokens: ThemeTokens, snapshot: dev.androidagent.settings.SettingsStatusSnapshot): View {
        val chips = listOf(
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_wifi, "Connected", "LAN", mapStatus(snapshot.connectedLan)),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_health, "Bridge", statusLabel(snapshot.bridgeHealthy), mapStatus(snapshot.bridgeHealthy)),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_lock, "Auth", if (snapshot.authOk == StatusLevel.Good) "OK" else "Off", mapStatus(snapshot.authOk)),
            SettingsComponents.statusChip(activity, tokens, R.drawable.ic_voice_idle, "Voice", if (snapshot.voiceIdle == StatusLevel.Active) "Active" else "Idle", if (snapshot.voiceIdle == StatusLevel.Active) StatusTone.Good else StatusTone.Idle)
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

    private fun renderSearchResults(activity: Activity, tokens: ThemeTokens, container: LinearLayout, query: String, callbacks: Callbacks) {
        container.removeAllViews()
        val results = SettingsSearchController.filter(query)
        if (results.isEmpty()) {
            container.addView(SettingsComponents.body(activity, tokens, "No matches.").apply {
                setPadding(0, SettingsComponents.dp(activity, DesignTokens.Spacing.md), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.md))
            })
            return
        }
        var lastGroup = ""
        results.forEach { entry ->
            if (entry.group != lastGroup) {
                lastGroup = entry.group
                container.addView(SettingsComponents.overline(activity, tokens, entry.group).apply {
                    setPadding(0, SettingsComponents.dp(activity, DesignTokens.Spacing.md), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm))
                })
            }
            val (icon, tone) = iconForEntry(entry)
            container.addView(SettingsComponents.categoryRow(
                context = activity,
                tokens = tokens,
                iconRes = icon,
                tone = tone,
                titleText = entry.title,
                subtitleText = entry.subtitle,
                onClick = { callbacks.navigate(entry.destination) }
            ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.xs))
        }
    }

    private fun iconForEntry(entry: SettingsSearchEntry): Pair<Int, BadgeTone> = when (entry.destination) {
        SettingsDestination.Runtime -> R.drawable.ic_terminal to BadgeTone.Teal
        SettingsDestination.Connection -> R.drawable.ic_link to BadgeTone.Blue
        SettingsDestination.Voice -> R.drawable.ic_voice_idle to BadgeTone.Violet
        SettingsDestination.Safety -> R.drawable.ic_shield to BadgeTone.Amber
        SettingsDestination.Appearance -> R.drawable.ic_palette to BadgeTone.Pink
        SettingsDestination.LocalModel -> R.drawable.ic_chip to BadgeTone.Slate
    }

    private fun showRunTargetMenu(activity: Activity, callbacks: Callbacks) {
        val tokens = SettingsComponents.tokens(activity)
        val options = listOf(AgentMode.Host to "Host bridge", AgentMode.Local to "Local phone")
        val current = AgentConfigStore.load(activity).agentMode
        val labels = options.map { it.second }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Run target")
            .setSingleChoiceItems(labels, options.indexOfFirst { it.first == current }) { d, which ->
                val selected = options[which].first
                val config = AgentConfigStore.load(activity)
                if (config.agentMode != selected) {
                    val updated = config.copy(
                        agentMode = selected,
                        experimentalLocalModelsEnabled = selected == AgentMode.Local || config.experimentalLocalModelsEnabled,
                        model = if (selected == AgentMode.Local) AgentModelOptions.LOCAL_LITERT_MODEL_ID else config.model
                    )
                    AgentConfigStore.save(activity, updated)
                    callbacks.onRunTargetChanged()
                }
                d.dismiss()
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Drawables.glassSurface(activity, tokens, DesignTokens.Radius.xl))
        }
        dialog.show()
    }
}
