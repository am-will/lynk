package dev.androidagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object PairingDeepLink {
    fun applyIntent(context: Context, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!isPairingUri(uri)) return false
        val urls = pairingUrls(uri)
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        val deviceId = (
            uri.getQueryParameter("deviceId")
                ?: uri.getQueryParameter("device_id")
                ?: uri.getQueryParameter("pairingId")
        )?.trim().orEmpty()
        if (urls.isEmpty() || token.isEmpty()) {
            Toast.makeText(context, "Pairing link is missing bridge URL or token.", Toast.LENGTH_LONG).show()
            return true
        }

        val current = AgentConfigStore.load(context)
        AgentConfigStore.save(
            context,
            current.copy(
                hostUrl = urls.first(),
                hostUrlCandidates = urls.drop(1),
                deviceId = deviceId.ifEmpty { current.deviceId },
                token = token
            )
        )
        Toast.makeText(context, "Host bridge pairing saved.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun isPairingUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        return (scheme == "android-agent" || scheme == "openclaw-agent") && host == "pair"
    }

    private fun pairingUrls(uri: Uri): List<String> {
        val values = mutableListOf<String>()
        uri.getQueryParameter("url")?.let { values.add(it) }
        uri.getQueryParameter("urls")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { values.addAll(it) }
        uri.getQueryParameters("endpoint")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { values.addAll(it) }
        return values.distinct()
    }
}
