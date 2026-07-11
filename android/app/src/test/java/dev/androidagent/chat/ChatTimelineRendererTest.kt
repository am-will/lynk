package dev.androidagent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineRendererTest {
    @Test
    fun emptyTimelineUsesEmptyState() {
        val plan = ChatTimelineRenderer.plan(ChatState(), showToolCalls = true)

        assertTrue(plan.isEmpty)
        assertEquals(emptyList<ChatTimelineItem>(), plan.items)
    }

    @Test
    fun hidesToolRowsWhenToolVisibilityIsDisabled() {
        val tool = item("tool", ChatTimelineKind.TOOL)
        val message = item("message", ChatTimelineKind.MESSAGE)
        val plan = ChatTimelineRenderer.plan(ChatState(timeline = listOf(tool, message)), showToolCalls = false)

        assertFalse(plan.isEmpty)
        assertEquals(listOf(message), plan.items)
    }

    @Test
    fun hidesClearingReasoningRows() {
        val clearing = item("clearing", ChatTimelineKind.REASONING, isClearing = true)
        val visible = item("visible", ChatTimelineKind.REASONING)
        val plan = ChatTimelineRenderer.plan(ChatState(timeline = listOf(clearing, visible)), showToolCalls = true)

        assertEquals(listOf(visible), plan.items)
    }

    @Test
    fun preservesExistingOrdering() {
        val items = listOf(
            item("first", ChatTimelineKind.MESSAGE),
            item("second", ChatTimelineKind.REASONING),
            item("third", ChatTimelineKind.TOOL)
        )
        val plan = ChatTimelineRenderer.plan(ChatState(timeline = items), showToolCalls = true)

        assertEquals(items, plan.items)
    }

    @Test
    fun identifiesSingleStreamingTextChangeForIncrementalRendering() {
        val oldStreaming = item("stream", ChatTimelineKind.MESSAGE).copy(text = "Hel", isStreaming = true)
        val newStreaming = oldStreaming.copy(text = "Hello")
        val previous = ChatTimelineRenderPlan(listOf(item("old", ChatTimelineKind.MESSAGE), oldStreaming), isEmpty = false)
        val current = ChatTimelineRenderPlan(listOf(item("old", ChatTimelineKind.MESSAGE), newStreaming), isEmpty = false)

        assertEquals(
            ChatTimelineTextUpdate("stream", "Hello", ChatTimelineKind.MESSAGE),
            ChatTimelineRenderer.streamingTextUpdate(previous, current)
        )
    }

    @Test
    fun rejectsStructuralAndTerminalChangesForIncrementalRendering() {
        val streaming = item("stream", ChatTimelineKind.MESSAGE).copy(text = "Hello", isStreaming = true)
        val previous = ChatTimelineRenderPlan(listOf(streaming), isEmpty = false)

        assertEquals(null, ChatTimelineRenderer.streamingTextUpdate(
            previous,
            ChatTimelineRenderPlan(listOf(streaming.copy(isStreaming = false)), isEmpty = false)
        ))
        assertEquals(null, ChatTimelineRenderer.streamingTextUpdate(
            previous,
            ChatTimelineRenderPlan(listOf(streaming, item("tool", ChatTimelineKind.TOOL)), isEmpty = false)
        ))
    }

    private fun item(
        id: String,
        kind: ChatTimelineKind,
        isClearing: Boolean = false
    ): ChatTimelineItem = ChatTimelineItem(
        id = id,
        kind = kind,
        role = "assistant",
        text = id,
        isClearing = isClearing
    )
}
