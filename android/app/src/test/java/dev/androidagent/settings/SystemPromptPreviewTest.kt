package dev.androidagent.settings

import dev.androidagent.settings.screens.SafetySettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPromptPreviewTest {
    @Test
    fun blankPromptSavesBlank() {
        assertEquals("", SafetySettingsScreen.savedSystemPrompt(""))
        assertEquals("", SafetySettingsScreen.savedSystemPrompt("   \n\t  "))
    }

    @Test
    fun blankPromptShowsEmptyState() {
        assertEquals("No system prompt configured.", SettingsUi.systemPromptPreview(""))
        assertEquals("No system prompt configured.", SettingsUi.systemPromptPreview("   \n\t  "))
    }

    @Test
    fun promptPreviewNormalizesWhitespace() {
        assertEquals("Use short answers.", SettingsUi.systemPromptPreview("  Use\nshort\tanswers.  "))
    }
}
