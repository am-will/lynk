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
