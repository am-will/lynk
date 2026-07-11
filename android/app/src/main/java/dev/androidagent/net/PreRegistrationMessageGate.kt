package dev.androidagent.net

import org.json.JSONObject

internal sealed interface PreRegistrationFrame {
    data class Registered(val text: String) : PreRegistrationFrame
    data class Status(val text: String, val status: String) : PreRegistrationFrame
    data class Rejected(val messageType: String) : PreRegistrationFrame
}

/** The only server messages accepted before the bridge authenticates this socket. */
internal object PreRegistrationMessageGate {
    fun evaluate(message: JSONObject, expectedDeviceId: String): PreRegistrationFrame {
        val type = message.optString("type")
        if (type != "agent_status") return PreRegistrationFrame.Rejected(type.ifBlank { "<missing>" })

        val text = message.optString("text")
        val expectedRegistration = "Registered $expectedDeviceId"
        return when {
            text == expectedRegistration -> PreRegistrationFrame.Registered(text)
            text.startsWith("Registered ") -> PreRegistrationFrame.Rejected(type)
            else -> PreRegistrationFrame.Status(text, message.optString("status", "info"))
        }
    }
}
