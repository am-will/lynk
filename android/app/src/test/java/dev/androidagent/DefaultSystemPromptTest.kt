package dev.androidagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSystemPromptTest {
    @Test
    fun `Android default keeps phone control instructions`() {
        assertFalse(DefaultSystemPrompt.text.isBlank())
        assertTrue(DefaultSystemPrompt.text.contains("Android phone"))
        assertTrue(DefaultSystemPrompt.text.contains("android-phone MCP tools"))
    }
}
