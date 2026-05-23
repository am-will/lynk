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
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.androidagent.AgentConfigStore
import dev.androidagent.AgentForegroundService
import dev.androidagent.R
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.settings.DiagnosticsBackendId
import dev.androidagent.settings.DiagnosticsBackendTestResult
import dev.androidagent.settings.DiagnosticsBackendTester
import dev.androidagent.settings.DiagnosticsEventLevel
import dev.androidagent.settings.DiagnosticsEventLog
import dev.androidagent.settings.DiagnosticsPrefsStore
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsComponents.BadgeTone
import dev.androidagent.settings.StatusLevel
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import kotlinx.coroutines.launch

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
            onBack = { navigateBack(activity) }
        ))

        val config = AgentConfigStore.load(activity)
        val localReady = LocalModelStore.exists(config.localModelPath)
        val serviceRunning = AgentForegroundService.isRunning
        val enabledHostBackends = listOfNotNull(
            "OpenClaw".takeIf { config.openClawHarnessEnabled },
            "Hermes".takeIf { config.hermesHarnessEnabled },
            "Codex".takeIf { config.codexHarnessEnabled }
        )

        // System status section
        root.addView(SettingsComponents.sectionHeader(activity, tokens, "System status"),
            SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm))

        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_activity,
            tone = BadgeTone.Teal,
            title = "Bridge link",
            subtitle = "PC bridge connection",
            details = when {
                config.token.isBlank() -> "Missing PHONE_AGENT_TOKEN. Backend tests cannot authenticate until Connection settings are paired."
                serviceRunning -> "Bubble service is running. Use Test Backends below to verify bridge and harness readiness."
                else -> "Bubble service is stopped. Start the bubble before using host backends."
            },
            statusText = when {
                config.token.isBlank() -> "Setup"
                serviceRunning -> "Running"
                else -> "Stopped"
            },
            tone2 = when {
                config.token.isBlank() -> StatusLevel.Warning
                serviceRunning -> StatusLevel.Good
                else -> StatusLevel.Idle
            }
        ))
        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_brand_circle,
            tone = BadgeTone.Blue,
            title = "Backend readiness",
            subtitle = if (enabledHostBackends.isEmpty()) "No host backends enabled" else "Enabled: ${enabledHostBackends.joinToString(", ")}",
            details = if (enabledHostBackends.isEmpty()) {
                "Enable at least one host backend in Models & Harness."
            } else {
                "Tap a backend test to check the PC bridge and that specific harness."
            },
            statusText = if (enabledHostBackends.isEmpty()) "Off" else "Test",
            tone2 = StatusLevel.Idle
        ), SettingsComponents.verticalMargin(activity, top = DesignTokens.Spacing.sm))
        root.addView(buildStatusCard(
            activity, tokens,
            iconRes = R.drawable.ic_chip,
            tone = BadgeTone.Violet,
            title = "Local model",
            subtitle = "LiteRT-LM",
            details = when {
                !config.experimentalLocalModelsEnabled -> "Local phone mode is disabled in Models & Harness."
                localReady -> "Imported model is available on ${config.localModelBackend.label}."
                else -> "Local phone mode is enabled, but no .litertlm model is imported."
            },
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
        details: String? = null,
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
        details?.takeIf { it.isNotBlank() }?.let { detailText ->
            copy.addView(TextView(activity).apply {
                text = detailText
                setTextColor(tokens.tertiaryText)
                textSize = DesignTokens.Text.caption
                includeFontPadding = false
                setPadding(0, SettingsComponents.dp(activity, 4), 0, 0)
            })
        }
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
            BackendEntry(DiagnosticsBackendId.OpenClaw, R.drawable.ic_brand_circle, BadgeTone.Teal),
            BackendEntry(DiagnosticsBackendId.Hermes, R.drawable.ic_chip, BadgeTone.Violet),
            BackendEntry(DiagnosticsBackendId.Codex, R.drawable.ic_terminal, BadgeTone.Blue),
            BackendEntry(DiagnosticsBackendId.Local, R.drawable.ic_file, BadgeTone.Slate)
        )
        entries.forEachIndexed { index, entry ->
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
                    runBackendTest(activity, entry.backend)
                }
            }
            cell.addView(TextView(activity).apply {
                text = entry.backend.label
                setTextColor(tokens.primaryText)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = DesignTokens.Text.footnote
                includeFontPadding = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
            cell.addView(SettingsComponents.iconBadge(activity, tokens, entry.iconRes, entry.tone, sizeDp = 36), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = SettingsComponents.dp(activity, DesignTokens.Spacing.sm)
            })
            cell.addView(TextView(activity).apply {
                text = "Test"
                setTextColor(tokens.secondaryText)
                textSize = DesignTokens.Text.caption
                includeFontPadding = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
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

    private fun runBackendTest(activity: Activity, backend: DiagnosticsBackendId) {
        val owner = activity as? ComponentActivity
        if (owner == null) {
            val result = DiagnosticsBackendTestResult(
                backend = backend,
                ok = false,
                level = DiagnosticsEventLevel.Error,
                title = "${backend.label} Test Failed",
                message = "Diagnostics tests need a ComponentActivity lifecycle."
            )
            showBackendResult(activity, result)
            return
        }
        owner.lifecycleScope.launch {
            val result = DiagnosticsBackendTester.test(activity, backend)
            showBackendResult(activity, result)
        }
    }

    private fun showBackendResult(activity: Activity, result: DiagnosticsBackendTestResult) {
        DiagnosticsEventLog.append(result.level, "${result.backend.label}: ${result.message}")
        AlertDialog.Builder(activity)
            .setTitle(result.title)
            .setMessage(result.message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun navigateBack(activity: Activity) {
        if (activity is ComponentActivity) {
            activity.onBackPressedDispatcher.onBackPressed()
            return
        }
        @Suppress("DEPRECATION")
        activity.onBackPressed()
    }

    private data class BackendEntry(
        val backend: DiagnosticsBackendId,
        val iconRes: Int,
        val tone: BadgeTone
    )
}
