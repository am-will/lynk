package dev.androidagent

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class PairingActivity : ComponentActivity() {
    private var pairingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingDialog?.dismiss()
        showPairingIntent(intent)
    }

    override fun onDestroy() {
        pairingDialog?.dismiss()
        pairingDialog = null
        super.onDestroy()
    }

    private fun showPairingIntent(intent: Intent?) {
        when (val result = PairingDeepLink.parse(intent?.dataString)) {
            PairingParseResult.NotPairingLink -> showInvalid("This is not a Lynk pairing link.")
            is PairingParseResult.Invalid -> showInvalid(result.reason)
            is PairingParseResult.Valid -> showConfirmation(result.request)
        }
    }

    private fun showConfirmation(request: PairingRequest) {
        if (request.nonce?.let { PairingNonceStore.wasConsumed(this, it) } == true) {
            showInvalid("This pairing link has already been used.")
            return
        }
        val current = AgentConfigStore.load(this)
        val model = PairingConfirmationModel.create(current, request)
        val message = TextView(this).apply {
            text = model.message
            textSize = 16f
            setTextIsSelectable(true)
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            val vertical = (8 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        pairingDialog = AlertDialog.Builder(this)
            .setTitle(model.title)
            .setView(message)
            .setPositiveButton("Approve pairing") { _, _ -> approve(current, request) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun approve(current: AgentConfig, request: PairingRequest) {
        val fresh = PairingDeepLink.parse(intent?.dataString)
        if (fresh !is PairingParseResult.Valid || fresh.request != request) {
            showInvalid((fresh as? PairingParseResult.Invalid)?.reason ?: "Pairing link is no longer valid.")
            return
        }
        if (request.nonce?.let { PairingNonceStore.consume(this, it) } == false) {
            showInvalid("This pairing link has already been used.")
            return
        }

        val updated = current.copy(
            hostUrl = request.endpoints.first(),
            hostUrlCandidates = request.endpoints.drop(1),
            deviceId = request.deviceId ?: current.deviceId,
            token = request.token
        )
        val changed = updated.hostUrl != current.hostUrl ||
            updated.hostUrlCandidates != current.hostUrlCandidates ||
            updated.deviceId != current.deviceId ||
            updated.token != current.token
        AgentConfigStore.save(this, updated)
        if (changed && AgentForegroundService.isRunning) {
            // Do not leave the old trusted connection running after replacing pairing.
            stopService(Intent(this, AgentForegroundService::class.java))
        }
        Toast.makeText(this, "Pairing approved. Start Lynk to connect.", Toast.LENGTH_LONG).show()
        openAppWithoutStartingBridge()
    }

    private fun openAppWithoutStartingBridge() {
        val destination = if (Settings.canDrawOverlays(this)) AppShellActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun showInvalid(reason: String) {
        pairingDialog = AlertDialog.Builder(this)
            .setTitle("Pairing link rejected")
            .setMessage(reason)
            .setPositiveButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}

internal data class PairingConfirmationModel(val title: String, val message: String) {
    companion object {
        fun create(current: AgentConfig, request: PairingRequest): PairingConfirmationModel {
            val replacement = current.token.isNotBlank() && (
                current.hostUrl != request.endpoints.first() ||
                    current.token != request.token ||
                    request.deviceId?.let { it != current.deviceId } == true
                )
            val title = if (replacement) "Replace host pairing?" else "Pair with host bridge?"
            val message = buildString {
                if (replacement) appendLine("Warning: this will replace the bridge currently trusted by Lynk.")
                if (request.isLegacy) appendLine("Legacy link: this request has no expiry or one-time nonce. Confirm its source carefully.")
                appendLine("Bridge endpoint${if (request.endpoints.size == 1) "" else "s"}:")
                request.endpoints.forEach { appendLine("  $it") }
                append("Device ID: ${request.deviceId ?: current.deviceId}\n\n")
                append("The authentication token is hidden. Only approve a link shown by a bridge you control.")
            }
            return PairingConfirmationModel(title, message)
        }
    }
}

private object PairingNonceStore {
    private const val PREFS = "lynk_pairing_nonces"
    private const val CONSUMED = "consumed"
    private const val MAX_REMEMBERED = 64

    fun wasConsumed(context: Context, nonce: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CONSUMED, emptySet()).orEmpty().contains(nonce)

    @Synchronized
    fun consume(context: Context, nonce: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(CONSUMED, emptySet()).orEmpty().toList()
        if (nonce in existing) return false
        val updated = (existing.takeLast(MAX_REMEMBERED - 1) + nonce).toSet()
        return prefs.edit().putStringSet(CONSUMED, updated).commit()
    }
}
