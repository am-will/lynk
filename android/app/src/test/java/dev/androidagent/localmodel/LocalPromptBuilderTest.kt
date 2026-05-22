package dev.androidagent.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptBuilderTest {
    @Test
    fun staticToolInstructionsStayInSystemPrompt() {
        val systemPrompt = LocalPromptBuilder.systemPrompt(
            basePrompt = "Base prompt",
            toolsAllowed = true,
            toolDescriptionsJson = """[{"id":"phone_observe"}]"""
        )
        val roundPrompt = LocalPromptBuilder.roundPrompt(
            transcript = listOf("user: Open Settings"),
            latestScreenshotPath = null
        )

        assertTrue(systemPrompt.contains("Available tools"))
        assertTrue(systemPrompt.contains("phone_observe"))
        assertTrue(roundPrompt.contains("Conversation:"))
        assertFalse(roundPrompt.contains("Available tools"))
        assertFalse(roundPrompt.contains("Use tools only when"))
    }

    @Test
    fun screenshotPromptCarriesOnlyDynamicVisualContext() {
        val roundPrompt = LocalPromptBuilder.roundPrompt(
            transcript = listOf("user: tap that"),
            latestScreenshotPath = "/tmp/screen.png"
        )

        assertTrue(roundPrompt.contains("screenshot image"))
        assertTrue(roundPrompt.contains("phone_tap_normalized"))
        assertTrue(roundPrompt.contains("user: tap that"))
    }
}
