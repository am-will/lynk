package dev.androidagent.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeIncomingMessageParserTest {
    @Test
    fun parsesValidBridgeMessage() {
        val result = BridgeIncomingMessageParser.parse("""{"type":"chat.message","text":"hi"}""")

        assertTrue(result.isSuccess)
        assertEquals("chat.message", result.getOrThrow().getString("type"))
    }

    @Test
    fun malformedJsonReturnsFailure() {
        val result = BridgeIncomingMessageParser.parse("{\"type\":\"chat.message\"")

        assertTrue(result.isFailure)
    }
}
