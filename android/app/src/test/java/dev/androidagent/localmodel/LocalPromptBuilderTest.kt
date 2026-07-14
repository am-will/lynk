package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
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
        assertTrue(systemPrompt.contains("phone_* tools directly"))
        assertFalse(systemPrompt.contains("local_read_skill"))
        assertTrue(systemPrompt.contains("<|lynk_control|>"))
        assertTrue(systemPrompt.contains("<|/lynk_control|>"))
        assertTrue(systemPrompt.contains("intentionally invalid"))
        assertFalse(LocalToolCallParser.parse(systemPrompt) is LocalModelOutput.ToolControl)
        assertTrue(roundPrompt.contains("Conversation:"))
        assertFalse(roundPrompt.contains("Available tools"))
        assertFalse(roundPrompt.contains("Use tools only when"))
    }

    @Test
    fun directPromptOmitsToolSchemaAndVerboseOverrides() {
        val systemPrompt = LocalPromptBuilder.systemPrompt(
            basePrompt = "Base prompt",
            toolsAllowed = false,
            toolDescriptionsJson = "large schema"
        )

        assertTrue(systemPrompt.contains("Base prompt"))
        assertTrue(systemPrompt.contains("Tools are not needed"))
        assertFalse(systemPrompt.contains("large schema"))
        assertFalse(systemPrompt.contains("Local mode override"))
    }

    @Test
    fun newestHistorySelectionUsesTokenBudgetInsteadOfMessageCount() {
        val history = (1..24).map { index -> message(index, "message-$index") }

        val selected = selectNewestHistory(
            history = history,
            runtimeProfile = profile(4096),
            systemPrompt = "system",
            currentUserText = "current"
        )

        assertEquals(24, selected.size)
        assertEquals("message-1", selected.first().text)
        assertEquals("message-24", selected.last().text)
    }

    @Test
    fun newestHistorySelectionKeepsCompactSuffixWithinAvailableBudget() {
        val history = listOf(
            message(1, "a".repeat(2000)),
            message(2, "b".repeat(2000)),
            message(3, "c".repeat(2000))
        )

        val selected = selectNewestHistory(
            history = history,
            runtimeProfile = profile(2048),
            systemPrompt = "system",
            currentUserText = "current"
        )

        assertEquals(listOf("c".repeat(2000)), selected.map { it.text })
    }

    @Test
    fun newestHistorySelectionReservesSystemCurrentToolAndOutputRoom() {
        val selected = selectNewestHistory(
            history = listOf(message(1, "old")),
            runtimeProfile = profile(512),
            systemPrompt = "s".repeat(800),
            currentUserText = "u".repeat(800)
        )

        assertTrue(selected.isEmpty())
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

    @Test
    fun historyBudgetUsesEffectiveRuntimeContext() {
        val history = listOf(
            message(1, "a".repeat(2000)),
            message(2, "b".repeat(2000)),
            message(3, "c".repeat(2000))
        )

        val selected = selectNewestHistory(
            history = history,
            runtimeProfile = profile(512),
            systemPrompt = "system",
            currentUserText = "current"
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun textOnlyRuntimeNeverReceivesInitialOrToolScreenshotImages() {
        assertEquals(
            emptyList<String>(),
            imagePathsForRound(
                runtimeProfile = profile(4096, supportsImageInput = false),
                initialImagePaths = listOf("/tmp/user.png"),
                latestScreenshotPath = "/tmp/screen.png"
            )
        )
    }

    @Test
    fun imageCapableRuntimePrefersLatestToolScreenshot() {
        assertEquals(
            listOf("/tmp/screen.png"),
            imagePathsForRound(
                runtimeProfile = profile(4096),
                initialImagePaths = listOf("/tmp/user.png"),
                latestScreenshotPath = "/tmp/screen.png"
            )
        )
    }

    private fun profile(contextTokens: Int, supportsImageInput: Boolean = true) = LocalModelRuntimeProfile(
        kind = LocalModelRuntimeKind.LiteRtLm,
        effectiveContextTokens = contextTokens,
        supportsImageInput = supportsImageInput
    )

    private fun message(index: Int, text: String) = LocalChatMessage(
        id = "message-$index",
        role = if (index % 2 == 0) "assistant" else "user",
        text = text,
        timestamp = index.toLong()
    )
}
