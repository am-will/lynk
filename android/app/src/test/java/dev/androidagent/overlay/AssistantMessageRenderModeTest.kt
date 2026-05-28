package dev.androidagent.overlay

import dev.androidagent.chat.ChatTimelineItem
import dev.androidagent.chat.ChatTimelineKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMessageRenderModeTest {
    @Test
    fun streamingAssistantWithoutTextShowsWaitingIndicator() {
        assertEquals(
            AssistantMessageRenderMode.WAITING,
            assistantMessageRenderMode(assistantItem(text = "", isStreaming = true))
        )
    }

    @Test
    fun streamingAssistantWithTextUsesPlainPartialText() {
        assertEquals(
            AssistantMessageRenderMode.STREAMING_TEXT,
            assistantMessageRenderMode(assistantItem(text = "Hel", isStreaming = true))
        )
    }

    @Test
    fun finalAssistantUsesFinalMarkdownRendering() {
        assertEquals(
            AssistantMessageRenderMode.FINAL,
            assistantMessageRenderMode(assistantItem(text = "**Hello**", isStreaming = false))
        )
    }

    private fun assistantItem(text: String, isStreaming: Boolean): ChatTimelineItem {
        return ChatTimelineItem(
            id = "assistant_run1",
            kind = ChatTimelineKind.MESSAGE,
            role = "assistant",
            text = text,
            runId = "run1",
            isStreaming = isStreaming
        )
    }
}
