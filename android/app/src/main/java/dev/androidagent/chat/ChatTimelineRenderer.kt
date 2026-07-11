package dev.androidagent.chat

data class ChatTimelineRenderPlan(
    val items: List<ChatTimelineItem>,
    val isEmpty: Boolean
)

data class ChatTimelineTextUpdate(
    val itemId: String,
    val text: String,
    val kind: ChatTimelineKind
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

    fun streamingTextUpdate(
        previous: ChatTimelineRenderPlan?,
        current: ChatTimelineRenderPlan
    ): ChatTimelineTextUpdate? {
        val oldItems = previous?.items ?: return null
        if (oldItems.size != current.items.size) return null
        val changedIndexes = oldItems.indices.filter { oldItems[it] != current.items[it] }
        if (changedIndexes.size != 1) return null
        val index = changedIndexes.single()
        val oldItem = oldItems[index]
        val newItem = current.items[index]
        val isStreamedText = newItem.isStreaming && (
            newItem.kind == ChatTimelineKind.REASONING ||
                (newItem.kind == ChatTimelineKind.MESSAGE && newItem.role == "assistant")
            )
        if (!isStreamedText || oldItem.copy(text = newItem.text) != newItem) return null
        return ChatTimelineTextUpdate(newItem.id, newItem.text, newItem.kind)
    }
}
