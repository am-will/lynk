package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCallParserTest {
    @Test
    fun acceptsOneExactVersionedControlFrame() {
        val output = LocalToolCallParser.parse(frame("""{"version":1,"type":"tool_call","tool":"phone_open_app","args":{"appName":"Settings"}}"""))
        val call = (output as LocalModelOutput.ToolControl).call

        assertEquals("phone_open_app", call.name)
        assertEquals("Settings", call.args.getString("appName"))
    }

    @Test
    fun ordinaryAndLegacyJsonRemainAssistantText() {
        listOf(
            "TASK_COMPLETE: Done.",
            "{\"tool\":\"phone_observe\",\"args\":{}}",
            "```json\n{\"tool\":\"phone_observe\",\"args\":{}}\n```",
            "<|tool_call>call:phone_observe{}<tool_call|>"
        ).forEach { text -> assertEquals(LocalModelOutput.AssistantText(text), LocalToolCallParser.parse(text)) }
    }

    @Test
    fun rejectsMarkersMixedWithProseOrMultipleFrames() {
        assertTrue(LocalToolCallParser.parse("Please run ${frame(validJson())}") is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse(frame(validJson()) + frame(validJson())) is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse("${LocalToolCallParser.OPEN}${validJson()}") is LocalModelOutput.InvalidControl)
    }

    @Test
    fun rejectsMalformedDuplicateAndAmbiguousJson() {
        assertTrue(LocalToolCallParser.parse(frame("""{"version":1,"type":"tool_call","tool":"phone_observe","args":BROKEN}""")) is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse(frame("""{"version":1,"type":"tool_call","tool":"phone_observe","tool":"termux_command","args":{}}""")) is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse(frame(validJson() + " trailing")) is LocalModelOutput.InvalidControl)
    }

    @Test
    fun rejectsUnknownVersionTypeAndRootFields() {
        assertTrue(LocalToolCallParser.parse(frame("""{"version":2,"type":"tool_call","tool":"phone_observe","args":{}}""")) is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse(frame("""{"version":1,"type":"assistant","tool":"phone_observe","args":{}}""")) is LocalModelOutput.InvalidControl)
        assertTrue(LocalToolCallParser.parse(frame("""{"version":1,"type":"tool_call","tool":"phone_observe","args":{},"extra":true}""")) is LocalModelOutput.InvalidControl)
    }

    private fun validJson() = """{"version":1,"type":"tool_call","tool":"phone_observe","args":{}}"""
    private fun frame(json: String) = LocalToolCallParser.OPEN + json + LocalToolCallParser.CLOSE
}
