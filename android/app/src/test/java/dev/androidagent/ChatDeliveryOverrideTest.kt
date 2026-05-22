package dev.androidagent

import dev.androidagent.agentchat.ChatSendDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDeliveryOverrideTest {
    @Test
    fun parsesQueuePromptOverride() {
        val parsed = parseChatDeliveryOverride("/queue \"some prompt\"")

        assertEquals(ChatSendDelivery.Queue, parsed?.delivery)
        assertEquals("some prompt", parsed?.text)
    }

    @Test
    fun parsesSteerPromptOverride() {
        val parsed = parseChatDeliveryOverride("/steer adjust the plan")

        assertEquals(ChatSendDelivery.Steer, parsed?.delivery)
        assertEquals("adjust the plan", parsed?.text)
    }

    @Test
    fun ignoresOtherSlashCommandsAndBlankOverrides() {
        assertNull(parseChatDeliveryOverride("/status"))
        assertNull(parseChatDeliveryOverride("/queue"))
        assertNull(parseChatDeliveryOverride("normal prompt"))
    }
}
