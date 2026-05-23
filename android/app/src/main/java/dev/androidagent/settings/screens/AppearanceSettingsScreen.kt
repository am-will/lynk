package dev.androidagent.settings.screens

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.androidagent.AppearancePrefs
import dev.androidagent.AppearancePrefsStore
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentForegroundService
import dev.androidagent.PanelAnimationStyle
import dev.androidagent.R
import dev.androidagent.avatar.AvatarConfigStore
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.avatar.AvatarSelection
import dev.androidagent.avatar.PetAsset
import dev.androidagent.settings.BubbleSizeMapper
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.exposeToAccessibility
import dev.androidagent.ui.updateAccessibilityState
import java.util.concurrent.Executors

object AppearanceSettingsScreen {

    interface Callbacks {
        fun onSaved()
        fun notifyAvatarChanged()
        fun notifyBubbleResize(sizeDp: Int)
        fun toggleAgentService()
        fun isAgentServiceRunning(): Boolean
        fun onBack()
    }

    fun build(activity: Activity, tokens: ThemeTokens, callbacks: Callbacks): View {
        val current = AppearancePrefsStore.load(activity)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(SettingsUi.toolbar(activity, "Appearance", tokens, callbacks::onBack))

        val toggleLabel = if (callbacks.isAgentServiceRunning()) "Stop Agent Bubble" else "Start Agent Bubble"
        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.sectionHeader(activity, "Agent Bubble", "Show or hide the floating agent bubble.", tokens))
            addView(
                SettingsUi.actionButton(
                    activity,
                    toggleLabel,
                    if (callbacks.isAgentServiceRunning()) dev.androidagent.settings.SettingsButtonTone.Secondary else dev.androidagent.settings.SettingsButtonTone.Primary,
                    tokens,
                    callbacks::toggleAgentService
                ).exposeToAccessibility(R.id.openclaw_agent_toggle_button, "Agent bubble toggle"),
                SettingsUi.stackedParams(activity, DesignTokens.Spacing.md)
            )
        }, SettingsUi.stackedParams(activity))

        val animationOptions = listOf(
            PanelAnimationStyle.Circular to "Circular reveal (from bubble)",
            PanelAnimationStyle.Slide to "Slide up from bottom"
        )
        val animationSpinner = SettingsUi.styledSpinner(
            activity,
            animationOptions.map { it.second },
            animationOptions.indexOfFirst { it.first == current.panelAnimation }.coerceAtLeast(0),
            tokens
        ).apply {
            exposeToAccessibility(R.id.openclaw_animation_spinner, "Panel animation")
        }

        val avatarSummary = SettingsUi.body(activity, currentAvatarSummary(activity), tokens).apply {
            exposeToAccessibility(description = "Current avatar")
            background = Drawables.glassInset(activity, tokens, DesignTokens.Radius.md)
            setPadding(
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2)
            )
        }

        val sizeValue = TextView(activity).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_bubble_size_value,
                description = "Bubble size",
                stateDescription = "${current.bubbleSizeDp} dp",
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            )
            setTextColor(tokens.primaryText)
            textSize = DesignTokens.Text.body
            text = "${current.bubbleSizeDp} dp"
        }
        var currentSizeDp = current.bubbleSizeDp
        var lastAppliedSizeDp = current.bubbleSizeDp
        val sizeSeekBar = SeekBar(activity).apply {
            exposeToAccessibility(R.id.openclaw_bubble_size_seekbar, "Bubble size slider")
            max = 100
            progress = BubbleSizeMapper.dpToProgress(current.bubbleSizeDp)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val dpValue = BubbleSizeMapper.progressToDp(progress)
                    currentSizeDp = dpValue
                    sizeValue.text = "$dpValue dp"
                    sizeValue.updateAccessibilityState(description = "Bubble size", stateDescription = "$dpValue dp")
                    if (fromUser && dpValue != lastAppliedSizeDp && AgentForegroundService.isRunning) {
                        callbacks.notifyBubbleResize(dpValue)
                        lastAppliedSizeDp = dpValue
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        root.addView(SettingsUi.card(activity, tokens).apply {
            addView(SettingsUi.labeledField(activity, "Panel animation", animationSpinner, tokens, DesignTokens.Spacing.md))
            addView(SettingsUi.body(activity, "How the chat modal appears when you tap the bubble.", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.xs))
            addView(SettingsUi.fieldLabel(activity, "Avatar", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.lg))
            addView(avatarSummary, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(
                SettingsUi.actionButton(activity, "Open Avatar Picker", dev.androidagent.settings.SettingsButtonTone.Secondary, tokens) {
                    showAvatarPicker(activity, tokens, avatarSummary, callbacks::notifyAvatarChanged)
                }.exposeToAccessibility(R.id.openclaw_avatar_picker_button, "Open avatar picker"),
                SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm + 2)
            )
            addView(SettingsUi.fieldLabel(activity, "Bubble size", tokens), SettingsUi.stackedParams(activity, DesignTokens.Spacing.lg))
            addView(sizeValue, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
            addView(sizeSeekBar, SettingsUi.stackedParams(activity, DesignTokens.Spacing.sm))
        }, SettingsUi.stackedParams(activity))

        root.addView(
            SettingsUi.actionButton(activity, "Save", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                AppearancePrefsStore.save(
                    activity,
                    AppearancePrefs(
                        panelAnimation = animationOptions.getOrElse(animationSpinner.selectedItemPosition) { animationOptions.first() }.first,
                        bubbleSizeDp = currentSizeDp
                    )
                )
                callbacks.onSaved()
                callbacks.onBack()
            },
            SettingsUi.stackedParams(activity, DesignTokens.Spacing.xl)
        )

        return root
    }

    private fun currentAvatarSummary(activity: Activity): String {
        return when (val selection = AvatarConfigStore.load(activity)) {
            is AvatarSelection.Lobster -> "Current: Lobster (default)"
            is AvatarSelection.Pet -> {
                val asset = AvatarLibrary.findCached(activity, selection.id)
                "Current: ${asset?.displayName ?: selection.id}"
            }
        }
    }

    private fun showAvatarPicker(activity: Activity, tokens: ThemeTokens, summaryView: TextView, onChanged: () -> Unit) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SettingsUi.dp(activity, DesignTokens.Spacing.xl), SettingsUi.dp(activity, DesignTokens.Spacing.md), SettingsUi.dp(activity, DesignTokens.Spacing.xl), SettingsUi.dp(activity, DesignTokens.Spacing.md))
        }
        val listView = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val statusView = SettingsUi.body(activity, "Scanning host...", tokens).apply {
            setPadding(0, SettingsUi.dp(activity, DesignTokens.Spacing.md), 0, 0)
            textSize = DesignTokens.Text.footnote
        }
        container.addView(listView)
        container.addView(statusView)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Avatar")
            .setView(ScrollView(activity).apply {
                addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton("Close", null)
            .create()

        val mainHandler = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor()
        val refresh: (List<PetAsset>) -> Unit = { pets ->
            listView.removeAllViews()
            val currentSelection = AvatarConfigStore.load(activity)
            listView.addView(avatarRow(activity, tokens, "Lobster", "Default mark.", currentSelection is AvatarSelection.Lobster) {
                AvatarConfigStore.save(activity, AvatarSelection.Lobster)
                onChanged()
                summaryView.text = currentAvatarSummary(activity)
                dialog.dismiss()
            })
            for (pet in pets) {
                listView.addView(avatarRow(activity, tokens, pet.displayName, pet.description.ifBlank { "Imported pet" }, currentSelection is AvatarSelection.Pet && currentSelection.id == pet.id) {
                    AvatarConfigStore.save(activity, AvatarSelection.Pet(pet.id))
                    onChanged()
                    summaryView.text = currentAvatarSummary(activity)
                    dialog.dismiss()
                })
            }
        }

        refresh(AvatarLibrary.listCached(activity))
        val config = AgentConfigStore.load(activity)
        executor.execute {
            val result = AvatarLibrary.refreshFromHost(activity.applicationContext, config.hostUrl, config.token)
            mainHandler.post {
                result.onSuccess { pets ->
                    refresh(pets)
                    statusView.text = if (pets.isEmpty()) "Connected to host. No pets found." else "Imported ${pets.size} pet(s) from host."
                }
                result.onFailure { error ->
                    statusView.text = "Could not reach host: ${error.message ?: "unknown error"}"
                }
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Drawables.glassSurface(activity, tokens, DesignTokens.Radius.xl))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(tokens.secondaryText)
        }
        dialog.setOnDismissListener { executor.shutdownNow() }
        dialog.show()
    }

    private fun avatarRow(activity: Activity, tokens: ThemeTokens, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.glassInset(activity, tokens, DesignTokens.Radius.md)
            setPadding(
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2),
                SettingsUi.dp(activity, DesignTokens.Spacing.lg),
                SettingsUi.dp(activity, DesignTokens.Spacing.md + 2)
            )
            isClickable = true
            isFocusable = true
            exposeToAccessibility(viewId = R.id.openclaw_avatar_row, description = "$title, $subtitle", stateDescription = if (selected) "selected" else "not selected")
            setOnClickListener { onClick() }
            addView(TextView(activity).apply {
                text = if (selected) "$title  •  selected" else title
                dev.androidagent.ui.Typography.applyCallout(this, tokens)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) tokens.accent else tokens.primaryText)
            })
            addView(SettingsUi.body(activity, subtitle, tokens).apply {
                setPadding(0, SettingsUi.dp(activity, DesignTokens.Spacing.xs), 0, 0)
            })
        }
    }
}
