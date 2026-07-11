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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.androidagent.chat.ChatAttachmentKind
import dev.androidagent.chat.ChatAttachmentStore
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
import kotlinx.coroutines.launch

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
    private var chatHost: FrameLayout? = null
    private val activityInstanceId = System.identityHashCode(this)
    private var shellChatAttachToken = 0
    private var settingsStatusPollScheduled = false
    private val settingsStatusPoll = object : Runnable {
        override fun run() {
            settingsStatusPollScheduled = false
            refreshShellState()
            if (selectedTab == ShellTab.Settings) {
                scheduleSettingsStatusPoll()
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selectedTab == ShellTab.Settings && settingsHost.handleBack()) {
                updateBackHandling()
                return
            }
            if (selectedTab == ShellTab.Chat && AgentForegroundService.consumeShellChatBackPress()) {
                updateBackHandling()
                return
            }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            updateBackHandling()
        }
    }

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

    private val chatImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleChatAttachmentPicked(uri, ChatAttachmentKind.IMAGE)
    }

    private val chatFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleChatAttachmentPicked(uri, ChatAttachmentKind.FILE)
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AgentForegroundService.ACTION_STATE_CHANGED) {
                refreshShellState()
            }
        }
    }
    private val minimizeAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MINIMIZE_APP) {
                moveTaskToBack(true)
            }
        }
    }
    private var serviceStateReceiverRegistered = false
    private var minimizeAppReceiverRegistered = false

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
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
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
        registerMinimizeAppReceiver()
        refreshShellState()
        if (selectedTab == ShellTab.Settings) {
            scheduleSettingsStatusPoll()
        }
    }

    override fun onStop() {
        stopSettingsStatusPoll()
        if (selectedTab == ShellTab.Chat) {
            detachShellChat()
        }
        unregisterMinimizeAppReceiver()
        unregisterServiceStateReceiver()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshShellState()
        if (selectedTab == ShellTab.Settings) {
            scheduleSettingsStatusPoll()
        }
        if (selectedTab == ShellTab.Chat) {
            chatHost?.takeIf { it.childCount == 0 }?.let { attachShellChat() }
        }
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
            override fun togglePetEnabled() = togglePetEnabledInternal()
        }, onNavigationChanged = ::updateBackHandling)

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
        if (selectedTab == ShellTab.Chat && tab != ShellTab.Chat) {
            detachShellChat()
        }
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
        chatHost = null
        when (tab) {
            ShellTab.Chat -> {
                contentHost.addView(buildChatTab())
                attachShellChat()
            }
            ShellTab.Voice -> contentHost.addView(buildVoiceTab())
            ShellTab.Activity -> contentHost.addView(ActivityDiagnosticsScreen.build(this, tokens))
            ShellTab.Settings -> {
                contentHost.addView(settingsContainer)
                settingsHost.showHub()
                scheduleSettingsStatusPoll()
            }
        }
        if (tab != ShellTab.Settings) {
            stopSettingsStatusPoll()
        }
        updateBackHandling()
    }

    private fun updateBackHandling() {
        backPressedCallback.isEnabled = selectedTab == ShellTab.Chat ||
            (selectedTab == ShellTab.Settings && settingsHost.canGoBack())
    }

    private fun buildChatTab(): View {
        return FrameLayout(this).apply {
            chatHost = this
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
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
            intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true -> selectTab(ShellTab.Chat)
        }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true) {
            requestMicPermissionInternal()
        }
        val attachmentKind = ChatAttachmentKind.fromWireValue(intent?.getStringExtra(EXTRA_REQUEST_CHAT_ATTACHMENT_KIND))
        if (intent?.hasExtra(EXTRA_REQUEST_CHAT_ATTACHMENT_KIND) == true) {
            intent.removeExtra(EXTRA_REQUEST_CHAT_ATTACHMENT_KIND)
            selectTab(ShellTab.Chat)
            launchChatAttachmentPicker(attachmentKind)
        }
    }

    private fun launchChatAttachmentPicker(kind: ChatAttachmentKind) {
        when (kind) {
            ChatAttachmentKind.IMAGE -> chatImagePicker.launch(arrayOf("image/*"))
            ChatAttachmentKind.FILE -> chatFilePicker.launch(arrayOf("*/*"))
        }
    }

    private fun handleChatAttachmentPicked(uri: Uri?, kind: ChatAttachmentKind) {
        if (uri == null) {
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "Attachment picker cancelled")
            return
        }
        lifecycleScope.launch {
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "Importing selected attachment")
            runCatching {
                ChatAttachmentStore(this@AppShellActivity).importUri(uri, kind)
            }.onSuccess { attachment ->
                val intent = Intent(this@AppShellActivity, AgentForegroundService::class.java)
                    .setAction(AgentForegroundService.ACTION_ADD_CHAT_ATTACHMENT)
                    .putExtra(AgentForegroundService.EXTRA_CHAT_ATTACHMENT_JSON, attachment.toStoredJson().toString())
                runCatching { startService(intent) }
                DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Attached ${attachment.displayName}")
            }.onFailure { error ->
                DiagnosticsEventLog.append(DiagnosticsEventLevel.Error, error.message ?: "Could not attach file")
            }
        }
    }

    private fun attachShellChat() {
        val host = chatHost ?: return
        shellChatAttachToken += 1
        AgentForegroundService.shellChatContainer = host
        AgentForegroundService.shellChatContainerActivityId = activityInstanceId
        AgentForegroundService.shellChatContainerToken = shellChatAttachToken
        ensureAgentService()
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_ATTACH_SHELL_CHAT)
            .putExtra(AgentForegroundService.EXTRA_SHELL_CHAT_ACTIVITY_ID, activityInstanceId)
            .putExtra(AgentForegroundService.EXTRA_SHELL_CHAT_TOKEN, shellChatAttachToken)
        runCatching { startService(intent) }
    }

    private fun detachShellChat() {
        val token = shellChatAttachToken
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_DETACH_SHELL_CHAT)
            .putExtra(AgentForegroundService.EXTRA_SHELL_CHAT_ACTIVITY_ID, activityInstanceId)
            .putExtra(AgentForegroundService.EXTRA_SHELL_CHAT_TOKEN, token)
        runCatching { startService(intent) }
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
        runCatching { startService(intent) }
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

    private fun togglePetEnabledInternal() {
        val config = AgentConfigStore.load(this)
        val nextEnabled = !config.petEnabled
        AgentConfigStore.save(this, config.copy(petEnabled = nextEnabled))
        if (nextEnabled) {
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Success, "Pet enabled")
            refreshPetVisibility(startIfNeeded = true)
        } else {
            DiagnosticsEventLog.append(DiagnosticsEventLevel.Info, "Pet disabled")
            refreshPetVisibility(startIfNeeded = false)
        }
        settingsHost.showHub()
    }

    private fun refreshPetVisibility(startIfNeeded: Boolean) {
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_REFRESH_PET_VISIBILITY)
        if (startIfNeeded) {
            runCatching { startService(intent) }
        } else if (AgentForegroundService.isRunning) {
            runCatching { startService(intent) }
        }
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
        val wasBridgeConnected = bridgeConnected
        bridgeConnected = AgentForegroundService.isBridgeConnected()
        if (
            wasBridgeConnected != bridgeConnected &&
            selectedTab == ShellTab.Settings &&
            ::settingsHost.isInitialized
        ) {
            settingsHost.refreshHubIfVisible()
        }
    }

    private fun scheduleSettingsStatusPoll() {
        if (settingsStatusPollScheduled) return
        settingsStatusPollScheduled = true
        mainHandler.postDelayed(settingsStatusPoll, SETTINGS_STATUS_POLL_MS)
    }

    private fun stopSettingsStatusPoll() {
        if (!settingsStatusPollScheduled) return
        mainHandler.removeCallbacks(settingsStatusPoll)
        settingsStatusPollScheduled = false
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

    private fun registerMinimizeAppReceiver() {
        if (minimizeAppReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            minimizeAppReceiver,
            IntentFilter(ACTION_MINIMIZE_APP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        minimizeAppReceiverRegistered = true
    }

    private fun unregisterMinimizeAppReceiver() {
        if (!minimizeAppReceiverRegistered) return
        unregisterReceiver(minimizeAppReceiver)
        minimizeAppReceiverRegistered = false
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
        const val EXTRA_REQUEST_CHAT_ATTACHMENT_KIND = "requestChatAttachmentKind"
        const val ACTION_MINIMIZE_APP = "app.lynk.action.MINIMIZE_APP"
        private const val REQUEST_MIC_PERMISSION = 20
        private const val REQUEST_LOCATION_PERMISSION = 21
        private const val REQUEST_NOTIFICATIONS = 10
        private const val SETTINGS_STATUS_POLL_MS = 1_000L

        fun openSettingsIntent(context: Context): Intent {
            return Intent(context, AppShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SHOW_SETTINGS, true)
        }

        fun openChatAttachmentPickerIntent(context: Context, kind: ChatAttachmentKind): Intent {
            return Intent(context, AppShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_CHAT, true)
                .putExtra(EXTRA_REQUEST_CHAT_ATTACHMENT_KIND, kind.wireValue)
        }
    }
}
