package dev.androidagent.overlay

import dev.androidagent.R
import dev.androidagent.chat.ChatHarnessModelGroup
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.unreadCountForHarness
import dev.androidagent.ui.AnchoredPicker

internal object ChatPickerRows {
    fun sessionRows(
        state: ChatState,
        sessions: List<ChatSessionRow>,
        limit: Int = 30,
        onSelectSession: (String) -> Unit
    ): List<AnchoredPicker.Row> {
        return sessions.take(limit).map { session ->
            AnchoredPicker.Row(
                id = "session:${session.key}",
                label = ChatPresentationHelpers.sessionLabel(session).take(40),
                sublabel = ChatPresentationHelpers.sessionSourceSublabel(session),
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = state.unreadCountForSession(session.key),
                selected = session.key == state.sessionKey,
                onSelect = { onSelectSession(session.key) }
            )
        }
    }

    fun harnessRows(
        state: ChatState,
        groups: List<ChatHarnessModelGroup>,
        activeHarnessId: String,
        onSelectHarness: (ChatHarnessModelGroup) -> Unit
    ): List<AnchoredPicker.Row> {
        return groups.map { group ->
            val unreadCount = state.unreadCountForHarness(group.id)
            AnchoredPicker.Row(
                id = "harness:${group.id}",
                label = group.label,
                sublabel = harnessSublabel(group.id == activeHarnessId, unreadCount),
                iconRes = R.drawable.ic_model,
                badgeCount = unreadCount,
                selected = group.id == activeHarnessId,
                onSelect = { onSelectHarness(group) }
            )
        }
    }

    fun harnessMenuRow(
        state: ChatState,
        currentHarnessLabel: String,
        onSelect: () -> Unit
    ): AnchoredPicker.Row {
        val unreadCount = state.totalUnreadReplies
        return AnchoredPicker.Row(
            id = "picker:harness",
            label = "Harness",
            sublabel = if (unreadCount > 0) {
                "$currentHarnessLabel, ${unreadReplySublabel(unreadCount)}"
            } else {
                currentHarnessLabel
            },
            iconRes = R.drawable.ic_model,
            badgeCount = unreadCount,
            dismissOnSelect = false,
            onSelect = onSelect
        )
    }

    private fun harnessSublabel(active: Boolean, unreadCount: Int): String? {
        return when {
            active && unreadCount > 0 -> "Active harness, ${unreadReplySublabel(unreadCount)}"
            active -> "Active harness"
            unreadCount > 0 -> unreadReplySublabel(unreadCount)
            else -> null
        }
    }

    private fun unreadReplySublabel(count: Int): String {
        return "$count unread ${if (count == 1) "reply" else "replies"}"
    }
}
