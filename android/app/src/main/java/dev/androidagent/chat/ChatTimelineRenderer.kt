package dev.androidagent.chat

data class ChatTimelineRenderPlan(
    val items: List<ChatTimelineItem>,
    val isEmpty: Boolean
)

object ChatTimelineRenderer {
    fun plan(state: ChatState, showToolCalls: Boolean): ChatTimelineRenderPlan {
        val visibleItems = state.timeline
            .filter { item -> item.kind != ChatTimelineKind.REASONING || !item.isClearing }
            .filter { item -> showToolCalls || item.kind != ChatTimelineKind.TOOL }
        return ChatTimelineRenderPlan(
            items = visibleItems,
            isEmpty = visibleItems.isEmpty()
        )
    }
}
