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
