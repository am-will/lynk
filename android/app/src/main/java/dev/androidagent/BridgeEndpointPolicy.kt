package dev.androidagent

import java.net.URI

enum class BridgeEndpointSecurity {
    Secure,
    LocalDevelopment,
    TrustedOverlayDevelopment
}

data class NormalizedBridgeEndpoint(val url: String, val security: BridgeEndpointSecurity)

object BridgeEndpointPolicy {
    fun normalize(raw: String, allowInsecureTrustedOverlay: Boolean = false): NormalizedBridgeEndpoint? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (scheme !in setOf("ws", "wss") || uri.userInfo != null || uri.fragment != null || uri.query != null) return null
        if (uri.port !in -1..65_535) return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/" && uri.path != "/phone") return null
        val normalized = URI(scheme, null, host, uri.port, "/phone", null, null).toString()
        return when {
            scheme == "wss" -> NormalizedBridgeEndpoint(normalized, BridgeEndpointSecurity.Secure)
            isLoopback(host) -> NormalizedBridgeEndpoint(normalized, BridgeEndpointSecurity.LocalDevelopment)
            allowInsecureTrustedOverlay && isTrustedOverlay(host) ->
                NormalizedBridgeEndpoint(normalized, BridgeEndpointSecurity.TrustedOverlayDevelopment)
            else -> null
        }
    }

    fun protectsProviderSecrets(raw: String): Boolean =
        normalize(raw)?.security == BridgeEndpointSecurity.Secure

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || Regex("^127(?:\\.\\d{1,3}){3}$").matches(host)

    private fun isTrustedOverlay(host: String): Boolean {
        if (host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:")) return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        return octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
    }
}
