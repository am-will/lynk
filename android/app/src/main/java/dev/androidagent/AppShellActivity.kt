package dev.androidagent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.settings.DiagnosticsEventLog
import dev.androidagent.settings.DiagnosticsEventLevel
import dev.androidagent.settings.SettingsComponents
import dev.androidagent.settings.SettingsDestination
import dev.androidagent.settings.SettingsHost
import dev.androidagent.settings.screens.ActivityDiagnosticsScreen
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import android.widget.ImageView

class AppShellActivity : ComponentActivity() {

    private enum class ShellTab(val label: String, val iconRes: Int) {
        Chat("Chat", R.drawable.ic_chat),
        Voice("Voice", R.drawable.ic_mic),
        Activity("Activity", R.drawable.ic_activity),
        Settings("Settings", R.drawable.ic_settings_gear)
    }

    private lateinit var contentHost: FrameLayout
    private lateinit var settingsContainer: FrameLayout
    private lateinit var settingsHost: SettingsHost
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLocalModelPathField: EditText? = null
    private var selectedTab = ShellTab.Chat
    private var bridgeConnected = false
    private var voiceActive = false

    private val localModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            LocalModelStore.importModel(this, uri)
        }.onSuccess { path ->
            pendingLocalModelPathField?.setText(path)
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Imported local model")
        }.onFailure { error ->
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Error, error.message ?: "Import failed")
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AgentForegroundService.ACTION_STATE_CHANGED) {
                refreshShellState()
            }
        }
    }
    private var serviceStateReceiverRegistered = false

    override fun attachBaseContext(newBase: Context) {
        // Force a dark UI configuration regardless of system theme.
        val cfg = android.content.res.Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        ensureAgentService()
        buildUi()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        registerServiceStateReceiver()
        refreshShellState()
    }

    override fun onStop() {
        unregisterServiceStateReceiver()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshShellState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION || requestCode == REQUEST_LOCATION_PERMISSION) {
            refreshShellState()
        }
    }

    private fun buildUi() {
        val tokens = tokens()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.background)
        }

        contentHost = FrameLayout(this)
        settingsContainer = FrameLayout(this)

        settingsHost = SettingsHost(this, settingsContainer, object : SettingsHost.Callbacks {
            override fun ensureAgentServiceRunning() = ensureAgentService()
            override fun refreshStatus() = refreshShellState()
            override fun onRunTargetChanged() = refreshShellState()
            override fun requestMicPermission() = requestMicPermissionInternal()
            override fun requestLocationPermission() = requestLocationPermissionInternal()
            override fun openAccessibilitySettings() {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            override fun openOverlaySettings() {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            override fun toggleAgentService() = toggleAgentServiceInternal()
            override fun isAgentServiceRunning(): Boolean = AgentForegroundService.isRunning
            override fun bridgeConnected(): Boolean = bridgeConnected
            override fun voiceActive(): Boolean = voiceActive
        })

        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomNav(tokens), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(root)
        selectTab(ShellTab.Chat)
    }

    private var bottomNavView: LinearLayout? = null

    private fun buildBottomNav(tokens: ThemeTokens): LinearLayout {
        val divider = View(this).apply {
            setBackgroundColor(tokens.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(tokens.surface)
            setPadding(
                dp(DesignTokens.Spacing.xs),
                dp(DesignTokens.Spacing.xs),
                dp(DesignTokens.Spacing.xs),
                dp(DesignTokens.Spacing.xs)
            )
            ShellTab.values().forEach { tab ->
                addView(bottomNavItem(tab, tokens) { selectTab(tab) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        bottomNavView = nav
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(divider)
        container.addView(nav)
        return container
    }

    private fun bottomNavItem(tab: ShellTab, tokens: ThemeTokens, onClick: () -> Unit): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(DesignTokens.Spacing.sm), 0, dp(DesignTokens.Spacing.sm))
            isClickable = true
            isFocusable = true
            background = Drawables.rounded(android.graphics.Color.TRANSPARENT, dp(8).toFloat())
            setOnClickListener { onClick() }
            tag = tab.name
        }
        val active = tab == selectedTab
        val color = if (active) tokens.accent else tokens.tertiaryText
        cell.addView(ImageView(this).apply {
            setImageResource(tab.iconRes)
            setColorFilter(color)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        cell.addView(TextView(this).apply {
            text = tab.label
            gravity = Gravity.CENTER
            textSize = DesignTokens.Text.caption
            setTextColor(color)
            includeFontPadding = false
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setPadding(0, dp(DesignTokens.Spacing.xs), 0, 0)
        })
        return cell
    }

    private fun selectTab(tab: ShellTab) {
        selectedTab = tab
        val tokens = tokens()
        bottomNavView?.let { nav ->
            ShellTab.values().forEachIndexed { index, shellTab ->
                val cell = nav.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
                val active = shellTab == tab
                val color = if (active) tokens.accent else tokens.tertiaryText
                (cell.getChildAt(0) as? ImageView)?.setColorFilter(color)
                (cell.getChildAt(1) as? TextView)?.apply {
                    setTextColor(color)
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                }
            }
        }
        contentHost.removeAllViews()
        when (tab) {
            ShellTab.Chat -> contentHost.addView(buildChatTab())
            ShellTab.Voice -> contentHost.addView(buildVoiceTab())
            ShellTab.Activity -> contentHost.addView(ActivityDiagnosticsScreen.build(this, tokens))
            ShellTab.Settings -> {
                contentHost.addView(settingsContainer)
                settingsHost.showHub()
            }
        }
    }

    private fun buildChatTab(): View {
        val tokens = tokens()
        return buildHeroTab(
            tokens = tokens,
            iconRes = R.drawable.ic_chat,
            tone = SettingsComponents.BadgeTone.Teal,
            title = "Chat",
            subtitle = "Open a fullscreen chat session. The floating bubble still opens the popup overlay.",
            primaryLabel = "Open Chat",
            primaryAction = { openFullscreenChat() }
        )
    }

    private fun buildVoiceTab(): View {
        val tokens = tokens()
        return buildHeroTab(
            tokens = tokens,
            iconRes = R.drawable.ic_mic,
            tone = SettingsComponents.BadgeTone.Violet,
            title = "Voice",
            subtitle = "Start a realtime voice session through the bridge.",
            primaryLabel = "Start Voice",
            primaryAction = { startVoiceSession() },
            secondaryLabel = "Voice Settings",
            secondaryAction = {
                selectTab(ShellTab.Settings)
                settingsHost.navigateTo(SettingsDestination.Voice)
            }
        )
    }

    private fun buildHeroTab(
        tokens: ThemeTokens,
        iconRes: Int,
        tone: SettingsComponents.BadgeTone,
        title: String,
        subtitle: String,
        primaryLabel: String,
        primaryAction: () -> Unit,
        secondaryLabel: String? = null,
        secondaryAction: (() -> Unit)? = null
    ): View {
        return ScrollView(this).apply {
            setBackgroundColor(tokens.background)
            isFillViewport = true
            addView(LinearLayout(this@AppShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(DesignTokens.Spacing.xxl),
                    dp(DesignTokens.Spacing.xxxl),
                    dp(DesignTokens.Spacing.xxl),
                    dp(DesignTokens.Spacing.xxxl)
                )
                // Big icon badge
                val badge = SettingsComponents.iconBadge(this@AppShellActivity, tokens, iconRes, tone, sizeDp = 88)
                addView(badge)
                addView(SettingsComponents.largeTitle(this@AppShellActivity, tokens, title).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(DesignTokens.Spacing.lg), 0, 0)
                })
                addView(SettingsComponents.body(this@AppShellActivity, tokens, subtitle, secondary = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(DesignTokens.Spacing.xl), dp(DesignTokens.Spacing.sm + 2), dp(DesignTokens.Spacing.xl), dp(DesignTokens.Spacing.xl))
                })
                addView(SettingsComponents.primaryButton(
                    this@AppShellActivity, tokens, primaryLabel,
                    tone = SettingsComponents.ButtonTone.Primary,
                    onClick = primaryAction
                ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (secondaryLabel != null && secondaryAction != null) {
                    addView(SettingsComponents.primaryButton(
                        this@AppShellActivity, tokens, secondaryLabel,
                        tone = SettingsComponents.ButtonTone.Outline,
                        onClick = secondaryAction
                    ).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(DesignTokens.Spacing.sm + 2)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(DesignTokens.Spacing.sm + 2)
                    })
                }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra(EXTRA_SHOW_SETTINGS, false) == true ||
                intent?.getBooleanExtra(MainActivity.EXTRA_SHOW_SETTINGS, false) == true -> {
                selectTab(ShellTab.Settings)
            }
            intent?.getStringExtra(EXTRA_INITIAL_TAB)?.equals("settings", ignoreCase = true) == true -> selectTab(ShellTab.Settings)
            intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true -> openFullscreenChat()
        }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true) {
            requestMicPermissionInternal()
        }
    }

    private fun openFullscreenChat() {
        ensureAgentService()
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_OPEN_CHAT)
            .putExtra(AgentForegroundService.EXTRA_PANEL_PRESENTATION, AgentForegroundService.PANEL_PRESENTATION_FULLSCREEN)
        runCatching { ContextCompat.startForegroundService(this, intent) }
        DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "Opened fullscreen chat")
    }

    private fun startVoiceSession() {
        ensureAgentService()
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_START_VOICE)
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun ensureAgentService() {
        if (!Settings.canDrawOverlays(this)) return
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_ENSURE_SERVICE)
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun toggleAgentServiceInternal() {
        if (AgentForegroundService.isRunning) {
            stopService(Intent(this, AgentForegroundService::class.java))
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "Agent bubble stopped")
        } else {
            ensureAgentService()
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Agent bubble started")
        }
        mainHandler.postDelayed({ refreshShellState() }, 150)
    }

    private fun requestMicPermissionInternal() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
        }
    }

    private fun requestLocationPermissionInternal() {
        if (!AgentLocationProvider.hasLocationPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    private fun refreshShellState() {
        bridgeConnected = AgentForegroundService.isRunning
    }

    private fun registerServiceStateReceiver() {
        if (serviceStateReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            IntentFilter(AgentForegroundService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        serviceStateReceiverRegistered = true
    }

    private fun unregisterServiceStateReceiver() {
        if (!serviceStateReceiverRegistered) return
        unregisterReceiver(serviceStateReceiver)
        serviceStateReceiverRegistered = false
    }

    private fun applySystemBars() {
        val tokens = tokens()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = tokens.surface
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }


    private fun tokens(): ThemeTokens = DesignTokens.darkOnly()
    private fun dp(value: Int): Int = DesignTokens.dp(this, value)

    fun registerLocalModelImport(field: EditText) {
        pendingLocalModelPathField = field
        localModelPicker.launch(arrayOf("*/*"))
    }

    companion object {
        const val EXTRA_SHOW_SETTINGS = "showSettings"
        const val EXTRA_INITIAL_TAB = "initialTab"
        const val EXTRA_OPEN_CHAT = "openChat"
        const val EXTRA_REQUEST_MIC_PERMISSION = "requestMicPermission"
        private const val REQUEST_MIC_PERMISSION = 20
        private const val REQUEST_LOCATION_PERMISSION = 21
        private const val REQUEST_NOTIFICATIONS = 10

        fun openSettingsIntent(context: Context): Intent {
            return Intent(context, AppShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SHOW_SETTINGS, true)
        }
    }
}
