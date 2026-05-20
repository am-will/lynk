package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCallParserTest {
    @Test
    fun parsesSingleToolCall() {
        val calls = LocalToolCallParser.parse("""{"tool":"phone_observe","args":{}}""")

        assertEquals(1, calls.size)
        assertEquals("phone_observe", calls.single().name)
    }

    @Test
    fun parsesToolCallArrayInsideFence() {
        val calls = LocalToolCallParser.parse(
            """
            ```json
            {"toolCalls":[{"name":"phone_open_app","args":{"appName":"Settings"}}]}
            ```
            """.trimIndent()
        )

        assertEquals(1, calls.size)
        assertEquals("phone_open_app", calls.single().name)
        assertEquals("Settings", calls.single().args.getString("appName"))
    }

    @Test
    fun ignoresNormalAssistantText() {
        val calls = LocalToolCallParser.parse("TASK_COMPLETE: Done.")

        assertTrue(calls.isEmpty())
    }
}
