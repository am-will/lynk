package dev.androidagent.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRegistrationMessageGateTest {
    @Test
    fun acceptsOnlyExactRegistrationAcknowledgement() {
        val accepted = PreRegistrationMessageGate.evaluate(
            JSONObject().put("type", "agent_status").put("text", "Registered pixel"),
            "pixel"
        )
        val wrongIdentity = PreRegistrationMessageGate.evaluate(
            JSONObject().put("type", "agent_status").put("text", "Registered attacker"),
            "pixel"
        )

        assertTrue(accepted is PreRegistrationFrame.Registered)
        assertTrue(wrongIdentity is PreRegistrationFrame.Rejected)
    }

    @Test
    fun permitsRegistrationStatusWithoutDispatchingIt() {
        val frame = PreRegistrationMessageGate.evaluate(
            JSONObject().put("type", "agent_status").put("status", "error").put("text", "Authentication pending"),
            "pixel"
        ) as PreRegistrationFrame.Status

        assertEquals("Authentication pending", frame.text)
        assertEquals("error", frame.status)
    }

    @Test
    fun rejectsHostileCommandAndChatFramesBeforeRegistration() {
        listOf(
            JSONObject().put("type", "command").put("command", "press_home"),
            JSONObject().put("type", "chat.message").put("text", "untrusted"),
            JSONObject().put("type", "realtime.sdp").put("sdp", "untrusted"),
            JSONObject().put("command", "press_home")
        ).forEach { message ->
            assertTrue(PreRegistrationMessageGate.evaluate(message, "pixel") is PreRegistrationFrame.Rejected)
        }
    }
}
