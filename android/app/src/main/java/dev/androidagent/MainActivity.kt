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
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.androidagent.avatar.AvatarLibrary
import dev.androidagent.localmodel.LocalModelStore
import dev.androidagent.settings.SettingsHost
import dev.androidagent.settings.SettingsUi
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography

/**
 * Setup-only fallback when overlay permission is missing.
 * When overlay is granted, [AppShellActivity] is the primary app surface.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsContainer: FrameLayout
    private lateinit var settingsHost: SettingsHost
    private lateinit var setupBanner: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLocalModelPathField: EditText? = null
    private var bridgeConnected = false

    private val localModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            LocalModelStore.importModel(this, uri)
        }.onSuccess { path ->
            pendingLocalModelPathField?.setText(path)
            setupBanner.text = "Imported local model."
        }.onFailure { error ->
            setupBanner.text = error.message ?: "Could not import local model."
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AgentForegroundService.ACTION_STATE_CHANGED) {
                refreshSetupState()
            }
        }
    }
    private var serviceStateReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(this, AppShellActivity::class.java).apply {
                    intent?.extras?.let { putExtras(it) }
                }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        applySystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        buildSetupUi()
        maybeRequestMicPermission(intent)
        AgentConfigStore.load(this).also { config ->
            AvatarLibrary.scanOnBoot(applicationContext, config.hostUrl, config.token)
        }
    }

    override fun onStart() {
        super.onStart()
        registerServiceStateReceiver()
        refreshSetupState()
    }

    override fun onStop() {
        unregisterServiceStateReceiver()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRequestMicPermission(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION || requestCode == REQUEST_LOCATION_PERMISSION) {
            refreshSetupState()
        }
    }

    fun registerLocalModelImport(field: EditText) {
        pendingLocalModelPathField = field
        localModelPicker.launch(arrayOf("*/*"))
    }

    private fun buildSetupUi() {
        val tokens = tokens()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.background)
        }

        setupBanner = TextView(this).apply {
            Typography.applyCallout(this, tokens)
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.lg), 0)
            text = "Grant overlay permission to enable the floating bubble and open the full app shell."
        }
        root.addView(setupBanner)

        root.addView(
            SettingsUi.actionButton(this, "Grant Overlay Permission", dev.androidagent.settings.SettingsButtonTone.Primary, tokens) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.lg), 0)
            }
        )

        settingsContainer = FrameLayout(this)
        settingsHost = SettingsHost(this, settingsContainer, settingsCallbacks())
        root.addView(settingsContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(root)
        settingsHost.showHub()
    }

    private fun settingsCallbacks() = object : SettingsHost.Callbacks {
        override fun ensureAgentServiceRunning() {
            if (Settings.canDrawOverlays(this@MainActivity)) {
                runCatching {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, AgentForegroundService::class.java)
                            .setAction(AgentForegroundService.ACTION_ENSURE_SERVICE)
                    )
                }
            }
        }
        override fun refreshStatus() = refreshSetupState()
        override fun requestMicPermission() = requestMicPermissionInternal()
        override fun requestLocationPermission() = requestLocationPermissionInternal()
        override fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        override fun openOverlaySettings() {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        override fun toggleAgentService() {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                setupBanner.text = "Grant overlay permission before starting the agent bubble."
                return
            }
            if (AgentForegroundService.isRunning) {
                stopService(Intent(this@MainActivity, AgentForegroundService::class.java))
            } else {
                ensureAgentServiceRunning()
            }
            mainHandler.postDelayed({ refreshSetupState() }, 150)
        }
        override fun isAgentServiceRunning(): Boolean = AgentForegroundService.isRunning
        override fun bridgeConnected(): Boolean = bridgeConnected
        override fun togglePetEnabled() = togglePetEnabledInternal()
    }

    private fun refreshSetupState() {
        if (!::setupBanner.isInitialized) return
        bridgeConnected = AgentForegroundService.isRunning
        if (!Settings.canDrawOverlays(this)) {
            setupBanner.text = "Grant overlay permission to enable the floating bubble and open the full app shell."
        } else {
            setupBanner.text = "Overlay granted. Re-open the app to enter the full shell."
        }
    }

    private fun togglePetEnabledInternal() {
        val config = AgentConfigStore.load(this)
        val nextEnabled = !config.petEnabled
        AgentConfigStore.save(this, config.copy(petEnabled = nextEnabled))
        if (nextEnabled && Settings.canDrawOverlays(this)) {
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AgentForegroundService::class.java)
                        .setAction(AgentForegroundService.ACTION_REFRESH_PET_VISIBILITY)
                )
            }
        } else if (AgentForegroundService.isRunning) {
            runCatching {
                startService(
                    Intent(this, AgentForegroundService::class.java)
                        .setAction(AgentForegroundService.ACTION_REFRESH_PET_VISIBILITY)
                )
            }
        }
        settingsHost.showHub()
    }

    private fun maybeRequestMicPermission(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true) {
            requestMicPermissionInternal()
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
        window.navigationBarColor = tokens.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !tokens.isDark
            isAppearanceLightNavigationBars = !tokens.isDark
        }
    }

    private fun tokens(): ThemeTokens = DesignTokens.resolve(this)
    private fun dp(value: Int): Int = DesignTokens.dp(this, value)

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "requestMicPermission"
        const val EXTRA_SHOW_SETTINGS = "showSettings"
        private const val REQUEST_MIC_PERMISSION = 20
        private const val REQUEST_LOCATION_PERMISSION = 21
        private const val REQUEST_NOTIFICATIONS = 10
    }
}
