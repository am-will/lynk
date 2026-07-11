package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PairingDeepLinkTest {
    private val now = 2_000_000_000L

    @Test
    fun parsesCurrentPairingRequestWithoutSideEffects() {
        val endpoint = encode("WSS://Bridge.Example:8788/phone")
        val result = PairingDeepLink.parse(
            "android-agent://pair?url=$endpoint&deviceId=pixel-8&token=secret&expiresAt=${now + 300}&nonce=abcdefghijklmnop",
            now
        ) as PairingParseResult.Valid

        assertEquals(listOf("wss://bridge.example:8788/phone"), result.request.endpoints)
        assertEquals("pixel-8", result.request.deviceId)
        assertFalse(result.request.isLegacy)
    }

    @Test
    fun acceptsLegacyLinkOnlyAsLegacyRequest() {
        val result = PairingDeepLink.parse(
            "openclaw-agent://pair?url=${encode("ws://192.168.1.10:8788/phone")}&token=secret",
            now
        ) as PairingParseResult.Valid

        assertTrue(result.request.isLegacy)
    }

    @Test
    fun rejectsExpiredOrIncompleteFreshnessData() {
        assertInvalid("android-agent://pair?url=${encode("wss://bridge.example/phone")}&token=x&expiresAt=${now - 1}&nonce=abcdefghijklmnop")
        assertInvalid("android-agent://pair?url=${encode("wss://bridge.example/phone")}&token=x&nonce=abcdefghijklmnop")
    }

    @Test
    fun rejectsUnsafeEndpointsAndMalformedIdentity() {
        assertInvalid("android-agent://pair?url=${encode("https://bridge.example/phone")}&token=x")
        assertInvalid("android-agent://pair?url=${encode("ws://user@bridge.example/phone")}&token=x")
        assertInvalid("android-agent://pair?url=${encode("ws://bridge.example/admin")}&token=x")
        assertInvalid("android-agent://pair?url=${encode("ws://bridge.example/phone?next=evil")}&token=x")
        assertInvalid("android-agent://pair?url=${encode("ws://bridge.example/phone")}&deviceId=bad%20id&token=x")
    }

    @Test
    fun ignoresUnrelatedLinks() {
        assertEquals(PairingParseResult.NotPairingLink, PairingDeepLink.parse("https://example.com/pair", now))
    }

    private fun assertInvalid(link: String) {
        assertTrue(PairingDeepLink.parse(link, now) is PairingParseResult.Invalid)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
