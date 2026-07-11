package dev.androidagent

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairingRequest(
    val endpoints: List<String>,
    val deviceId: String?,
    val token: String,
    val expiresAtEpochSeconds: Long?,
    val nonce: String?
) {
    val isLegacy: Boolean
        get() = expiresAtEpochSeconds == null || nonce == null
}

sealed interface PairingParseResult {
    data object NotPairingLink : PairingParseResult
    data class Valid(val request: PairingRequest) : PairingParseResult
    data class Invalid(val reason: String) : PairingParseResult
}

/** Parses pairing links without changing active configuration. */
object PairingDeepLink {
    private const val MAX_LINK_LENGTH = 32_768
    private const val MAX_ENDPOINTS = 8
    private const val MAX_TOKEN_LENGTH = 4_096
    private const val MAX_VALIDITY_SECONDS = 15 * 60
    private val deviceIdPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val noncePattern = Regex("^[A-Za-z0-9_-]{16,128}$")

    fun parse(raw: String?, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): PairingParseResult {
        if (raw.isNullOrBlank()) return PairingParseResult.NotPairingLink
        if (raw.length > MAX_LINK_LENGTH) return PairingParseResult.Invalid("Pairing link is too large.")

        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return PairingParseResult.Invalid("Pairing link is malformed.")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in setOf("android-agent", "openclaw-agent") || host != "pair") {
            return PairingParseResult.NotPairingLink
        }
        if (!uri.fragment.isNullOrEmpty() || !uri.userInfo.isNullOrEmpty()) {
            return PairingParseResult.Invalid("Pairing link contains unsupported URL components.")
        }

        val parameters = parseQuery(uri.rawQuery)
            ?: return PairingParseResult.Invalid("Pairing link contains invalid encoding.")
        val endpointValues = buildList {
            parameters["url"]?.firstOrNull()?.let(::add)
            parameters["urls"]?.forEach { addAll(it.split(',')) }
            parameters["endpoint"]?.let(::addAll)
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        if (endpointValues.isEmpty()) return PairingParseResult.Invalid("Pairing link is missing a bridge URL.")
        if (endpointValues.size > MAX_ENDPOINTS) return PairingParseResult.Invalid("Pairing link has too many bridge URLs.")
        val endpoints = endpointValues.map { normalizeEndpoint(it) ?: return PairingParseResult.Invalid("Pairing link contains an unsafe bridge URL.") }

        val token = parameters["token"]?.singleOrNull()?.trim().orEmpty()
        if (token.isEmpty()) return PairingParseResult.Invalid("Pairing link is missing an authentication token.")
        if (token.length > MAX_TOKEN_LENGTH) return PairingParseResult.Invalid("Pairing token is too large.")

        val deviceId = listOf("deviceId", "device_id", "pairingId")
            .firstNotNullOfOrNull { parameters[it]?.singleOrNull()?.trim()?.takeIf(String::isNotEmpty) }
        if (deviceId != null && !deviceIdPattern.matches(deviceId)) {
            return PairingParseResult.Invalid("Pairing link contains an invalid device ID.")
        }

        val expiresRaw = parameters["expiresAt"]?.singleOrNull()
            ?: parameters["expires_at"]?.singleOrNull()
        val nonce = parameters["nonce"]?.singleOrNull()?.trim()?.takeIf(String::isNotEmpty)
        if ((expiresRaw == null) != (nonce == null)) {
            return PairingParseResult.Invalid("Pairing link must include both expiry and nonce.")
        }
        val expiresAt = expiresRaw?.toLongOrNull()
        if (expiresRaw != null && expiresAt == null) return PairingParseResult.Invalid("Pairing link has an invalid expiry.")
        if (expiresAt != null && expiresAt <= nowEpochSeconds) return PairingParseResult.Invalid("Pairing link has expired.")
        if (expiresAt != null && expiresAt - nowEpochSeconds > MAX_VALIDITY_SECONDS) {
            return PairingParseResult.Invalid("Pairing link expiry is too far in the future.")
        }
        if (nonce != null && !noncePattern.matches(nonce)) return PairingParseResult.Invalid("Pairing link has an invalid nonce.")

        return PairingParseResult.Valid(PairingRequest(endpoints, deviceId, token, expiresAt, nonce))
    }

    private fun normalizeEndpoint(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("ws", "wss")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.query != null) return null
        if (uri.port !in -1..65_535) return null
        if (uri.path.isNullOrEmpty() || uri.path == "/") return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, "/phone", null, null).toString()
        if (uri.path != "/phone") return null
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, "/phone", null, null).toString()
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>>? {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val values = linkedMapOf<String, MutableList<String>>()
        for (part in rawQuery.split('&')) {
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0]) ?: return null
            val value = decode(pieces.getOrElse(1) { "" }) ?: return null
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
