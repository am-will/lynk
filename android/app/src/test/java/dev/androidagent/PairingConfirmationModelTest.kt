package dev.androidagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConfirmationModelTest {
    @Test
    fun warnsWhenReplacingExistingTrustAndNeverDisplaysToken() {
        val current = config(hostUrl = "ws://old.example/phone", token = "old-secret")
        val request = PairingRequest(
            endpoints = listOf("wss://new.example/phone"),
            deviceId = "pixel",
            token = "new-secret",
            expiresAtEpochSeconds = 2_000_000_300,
            nonce = "abcdefghijklmnop"
        )

        val model = PairingConfirmationModel.create(current, request)

        assertTrue(model.title.contains("Replace"))
        assertTrue(model.message.contains("replace the bridge"))
        assertTrue(model.message.contains("wss://new.example/phone"))
        assertFalse(model.message.contains("new-secret"))
        assertFalse(model.message.contains("old-secret"))
    }

    @Test
    fun legacyRequestGetsProminentWarning() {
        val model = PairingConfirmationModel.create(
            config(hostUrl = "ws://bridge.example/phone", token = ""),
            PairingRequest(listOf("ws://bridge.example/phone"), null, "secret", null, null)
        )

        assertTrue(model.message.contains("Legacy link"))
    }

    @Test
    fun insecureOverlayRequestGetsProminentWarning() {
        val model = PairingConfirmationModel.create(
            config(hostUrl = "ws://127.0.0.1/phone", token = ""),
            PairingRequest(
                listOf("ws://100.88.12.34:8788/phone"),
                null,
                "secret",
                2_000_000_300,
                "abcdefghijklmnop",
                allowInsecureTrustedOverlay = true
            )
        )

        assertTrue(model.message.contains("cleartext WebSocket"))
        assertTrue(model.message.contains("Tailscale"))
    }

    private fun config(hostUrl: String, token: String) = AgentConfig(
        hostUrl = hostUrl,
        deviceId = "pixel",
        token = token,
        openAiApiKey = "",
        systemPrompt = "",
        model = "",
        reasoningEffort = "medium"
    )
}
