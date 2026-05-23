package dev.androidagent.settings.screens

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.androidagent.AgentConfigStore
import dev.androidagent.R
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.settings.ColorUtils
import dev.androidagent.settings.DiagnosticsEventLevel
import dev.androidagent.settings.DiagnosticsEventLog
import dev.androidagent.settings.DiagnosticsPrefsStore
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsComponents.BadgeTone
import dev.androidagent.settings.SettingsComponents.ButtonTone
import dev.androidagent.settings.SettingsStatusProvider
import dev.androidagent.settings.StatusLevel
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens

object ActivityDiagnosticsScreen {

    fun build(activity: Activity, tokens: ThemeTokens): View {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(tokens.background)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
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

        root.addView(SettingsComponents.subscreenHeader(
            context = activity,
            tokens = tokens,
            titleText = "Developer Diagnostics",
            onBack = { activity.onBackPressed() }
        ))

        val snapshot = SettingsStatusProvider.snapshot(activity)
        val config = AgentConfigStore.load(activity)
        val localReady = LocalModelStore.exists(config.localModelPath)

        // System status section
        root.addView(SettingsComponents.sectionHeader(activity, tokens, "System status"),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm))

        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_activity,
            tone = BadgeTone.Teal,
            title = "Bridge health",
            subtitle = "PC bridge connection",
            statusText = snapshot.bridgeHealthy.label,
            tone2 = snapshot.bridgeHealthy
        ))
        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_brand_circle,
            tone = BadgeTone.Blue,
            title = "Harness health",
            subtitle = "Agent harness & tools",
            statusText = snapshot.bridgeHealthy.label,
            tone2 = snapshot.bridgeHealthy
        ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm))
        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_chip,
            tone = BadgeTone.Violet,
            title = "Local model",
            subtitle = "LiteRT-LM",
            statusText = if (localReady) "Ready" else "Missing",
            tone2 = if (localReady) StatusLevel.Good else StatusLevel.Warning
        ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm))

        // Recent events
        root.addView(SettingsComponents.sectionHeader(activity, tokens, "Recent events", trailingText = "View all") { /* future */ },
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        root.addView(buildRecentEventsCard(activity, tokens))

        // Test backends 2x2
        root.addView(SettingsComponents.sectionHeader(activity, tokens, "Test backends"),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        root.addView(buildTestBackendsGrid(activity, tokens))

        // Logs
        root.addView(SettingsComponents.sectionHeader(activity, tokens, "Logs"),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.lg))

        root.addView(buildLogsCard(activity, tokens))

        // Danger zone
        root.addView(buildDangerZone(activity, tokens),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.xl))

        scroll.addView(root)
        return scroll
    }

    private fun buildStatusCard(
        activity: Activity,
        tokens: ThemeTokens,
        iconRes: Int,
        tone: BadgeTone,
        title: String,
        subtitle: String,
        statusText: String,
        tone2: StatusLevel
    ): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(SettingsComponents.iconBadge(activity, tokens, iconRes, tone, sizeDp = 38))

        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SettingsComponents.dp(activity, DesignTokens.Spacing.md), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm), 0)
        }
        copy.addView(TextView(activity).apply {
            text = title
            setTextColor(tokens.primaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        copy.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val statusColor = when (tone2) {
            StatusLevel.Good, StatusLevel.Active -> tokens.success
            StatusLevel.Warning -> tokens.warning
            StatusLevel.Bad -> tokens.danger
            StatusLevel.Idle -> tokens.secondaryText
        }
        row.addView(TextView(activity).apply {
            text = statusText
            setTextColor(statusColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
        })
        row.addView(View(activity).apply {
            background = Drawables.circle(fill = statusColor)
            layoutParams = LinearLayout.LayoutParams(
                SettingsComponents.dp(activity, 8),
                SettingsComponents.dp(activity, 8)
            ).apply { marginStart = SettingsComponents.dp(activity, DesignTokens.Spacing.sm) }
        })
        card.addView(row)
        return card
    }

    private fun buildRecentEventsCard(activity: Activity, tokens: ThemeTokens): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)
        val events = DiagnosticsEventLog.recent()
        if (events.isEmpty()) {
            card.addView(TextView(activity).apply {
                text = "No recent events"
                setTextColor(tokens.secondaryText)
                textSize = DesignTokens.Text.footnote
            })
        } else {
            events.take(6).forEachIndexed { index, event ->
                if (index > 0) {
                    card.addView(SettingsComponents.hairline(activity, tokens, topDp = DesignTokens.Spacing.xs, bottomDp = DesignTokens.Spacing.xs))
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, SettingsComponents.dp(activity, DesignTokens.Spacing.xs), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.xs))
                }
                row.addView(TextView(activity).apply {
                    text = event.message
                    setTextColor(tokens.primaryText)
                    textSize = DesignTokens.Text.footnote
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(activity).apply {
                    text = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date(event.timestampMs))
                    setTextColor(tokens.tertiaryText)
                    textSize = DesignTokens.Text.caption
                    includeFontPadding = false
                    setPadding(SettingsComponents.dp(activity, DesignTokens.Spacing.sm), 0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm), 0)
                })
                val dotColor = when (event.level) {
                    DiagnosticsEventLevel.Success, DiagnosticsEventLevel.Info -> tokens.success
                    DiagnosticsEventLevel.Warning -> tokens.warning
                    DiagnosticsEventLevel.Error -> tokens.danger
                }
                row.addView(View(activity).apply {
                    background = Drawables.circle(fill = dotColor)
                    layoutParams = LinearLayout.LayoutParams(
                        SettingsComponents.dp(activity, 7),
                        SettingsComponents.dp(activity, 7)
                    )
                })
                card.addView(row)
            }
        }
        return card
    }

    private fun buildTestBackendsGrid(activity: Activity, tokens: ThemeTokens): LinearLayout {
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val entries = listOf(
            Triple(R.drawable.ic_brand_circle, "OpenClaw", BadgeTone.Teal),
            Triple(R.drawable.ic_chip, "Hermes", BadgeTone.Violet),
            Triple(R.drawable.ic_terminal, "Codex", BadgeTone.Blue),
            Triple(R.drawable.ic_file, "Local", BadgeTone.Slate)
        )
        entries.forEachIndexed { index, (icon, label, tone) ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = SettingsComponents.darkCardBackground(activity, tokens)
                setPadding(
                    SettingsComponents.dp(activity, DesignTokens.Spacing.sm),
                    SettingsComponents.dp(activity, DesignTokens.Spacing.md - 2),
                    SettingsComponents.dp(activity, DesignTokens.Spacing.sm),
                    SettingsComponents.dp(activity, DesignTokens.Spacing.md - 2)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "$label backend tested")
                }
            }
            cell.addView(SettingsComponents.iconBadge(activity, tokens, icon, tone, sizeDp = 36))
            cell.addView(TextView(activity).apply {
                text = label
                setTextColor(tokens.primaryText)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = DesignTokens.Text.footnote
                includeFontPadding = false
                setPadding(0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm), 0, 0)
            })
            cell.addView(TextView(activity).apply {
                text = "Test"
                setTextColor(tokens.secondaryText)
                textSize = DesignTokens.Text.caption
                includeFontPadding = false
                setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
            })
            grid.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = SettingsComponents.dp(activity, DesignTokens.Spacing.sm)
            })
        }
        return grid
    }

    private fun buildLogsCard(activity: Activity, tokens: ThemeTokens): LinearLayout {
        val card = SettingsComponents.card(activity, tokens, padding = DesignTokens.Spacing.md)

        // Export logs row
        val exportRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, DiagnosticsEventLog.exportText())
                }
                activity.startActivity(Intent.createChooser(share, "Export logs"))
            }
        }
        val copy1 = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy1.addView(TextView(activity).apply {
            text = "Export logs"
            setTextColor(tokens.primaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        copy1.addView(TextView(activity).apply {
            text = "Save diagnostics to file"
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        exportRow.addView(copy1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        exportRow.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_share)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(
                SettingsComponents.dp(activity, 20),
                SettingsComponents.dp(activity, 20)
            )
        })
        card.addView(exportRow)

        card.addView(SettingsComponents.hairline(activity, tokens, topDp = DesignTokens.Spacing.md, bottomDp = DesignTokens.Spacing.md))

        // Log level row
        val levelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val levels = DiagnosticsPrefsStore.LogLevel.values()
                val current = DiagnosticsPrefsStore.logLevel(activity)
                AlertDialog.Builder(activity)
                    .setTitle("Log level")
                    .setSingleChoiceItems(levels.map { it.label }.toTypedArray(), levels.indexOf(current)) { d, which ->
                        DiagnosticsPrefsStore.setLogLevel(activity, levels[which])
                        d.dismiss()
                    }
                    .show()
            }
        }
        levelRow.addView(TextView(activity).apply {
            text = "Log level"
            setTextColor(tokens.primaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        levelRow.addView(TextView(activity).apply {
            text = DiagnosticsPrefsStore.logLevel(activity).label
            setTextColor(tokens.secondaryText)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        levelRow.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(tokens.tertiaryText)
            layoutParams = LinearLayout.LayoutParams(
                SettingsComponents.dp(activity, 16),
                SettingsComponents.dp(activity, 16)
            ).apply { marginStart = SettingsComponents.dp(activity, DesignTokens.Spacing.sm) }
        })
        card.addView(levelRow)

        return card
    }

    private fun buildDangerZone(activity: Activity, tokens: ThemeTokens): LinearLayout {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(activity).apply {
            text = "Danger zone"
            setTextColor(tokens.danger)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, 0, 0, SettingsComponents.dp(activity, DesignTokens.Spacing.sm))
            letterSpacing = 0.05f
        })
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.rounded(
                fill = ColorUtils.with(tokens.danger, 0x1F),
                radius = SettingsComponents.dp(activity, DesignTokens.Radius.lg).toFloat(),
                strokeColor = ColorUtils.with(tokens.danger, 0x66),
                strokeWidth = SettingsComponents.dp(activity, 1).coerceAtLeast(1)
            )
            setPadding(
                SettingsComponents.dp(activity, DesignTokens.Spacing.md),
                SettingsComponents.dp(activity, DesignTokens.Spacing.md),
                SettingsComponents.dp(activity, DesignTokens.Spacing.md),
                SettingsComponents.dp(activity, DesignTokens.Spacing.md)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmReset(activity, tokens) }
        }
        card.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(tokens.danger)
            layoutParams = LinearLayout.LayoutParams(
                SettingsComponents.dp(activity, 22),
                SettingsComponents.dp(activity, 22)
            )
        })
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SettingsComponents.dp(activity, DesignTokens.Spacing.sm + 2), 0, 0, 0)
        }
        copy.addView(TextView(activity).apply {
            text = "Reset all data & settings"
            setTextColor(tokens.danger)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DesignTokens.Text.callout
            includeFontPadding = false
        })
        copy.addView(TextView(activity).apply {
            text = "This cannot be undone"
            setTextColor(ColorUtils.with(tokens.danger, 0xCC))
            textSize = DesignTokens.Text.footnote
            includeFontPadding = false
            setPadding(0, SettingsComponents.dp(activity, 2), 0, 0)
        })
        card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        column.addView(card)
        return column
    }

    private fun confirmReset(activity: Activity, tokens: ThemeTokens) {
        AlertDialog.Builder(activity)
            .setTitle("Reset all data?")
            .setMessage("This clears agent, appearance, avatar, and diagnostics preferences.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                activity.getSharedPreferences("open_claw_agent_config", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                activity.getSharedPreferences("open_claw_agent_appearance", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                activity.getSharedPreferences("avatar_config", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                activity.getSharedPreferences("open_claw_agent_diagnostics", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                DiagnosticsEventLog.clear()
                DiagnosticsEventLog.append(DiagnosticsEventLevel.Warning, "All settings reset")
            }
            .show()
    }
}
