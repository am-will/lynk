package dev.androidagent.overlay

import dev.androidagent.CodexWorkspacePaths
import dev.androidagent.R
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.ui.AnchoredPicker

object CodexSessionPickerSections {
    fun build(
        sessions: List<ChatSessionRow>,
        selectedSessionKey: String?,
        unreadCountForSession: (String) -> Int,
        onSelectSession: (String) -> Unit,
        limit: Int = Int.MAX_VALUE
    ): List<AnchoredPicker.Section> {
        val scoped = sessions.take(limit)
        val workspaceSessions = scoped.filter { !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank() }
        val quickChatSessions = scoped.filter { it.workspacePath.isNullOrBlank() && it.workspaceName.isNullOrBlank() }
        val sections = mutableListOf<AnchoredPicker.Section>()

        workspaceSessions
            .groupBy { it.workspacePath ?: it.workspaceName ?: "Workspace" }
            .forEach { (workspaceKey, groupedSessions) ->
                val title = groupedSessions.firstOrNull()?.workspacePath?.let(CodexWorkspacePaths::display)
                    ?: groupedSessions.firstOrNull()?.workspaceName
                    ?: CodexWorkspacePaths.display(workspaceKey)
                sections.add(AnchoredPicker.Section(
                    title,
                    rows(
                        sessions = groupedSessions,
                        selectedSessionKey = selectedSessionKey,
                        unreadCountForSession = unreadCountForSession,
                        onSelectSession = onSelectSession
                    )
                ))
            }

        if (quickChatSessions.isNotEmpty()) {
            sections.add(AnchoredPicker.Section(
                "QuickChats",
                rows(
                    sessions = quickChatSessions,
                    selectedSessionKey = selectedSessionKey,
                    unreadCountForSession = unreadCountForSession,
                    onSelectSession = onSelectSession
                )
            ))
        }

        return sections
    }

    fun workspaceCount(sessions: List<ChatSessionRow>): Int {
        return sessions.mapNotNull { it.workspacePath ?: it.workspaceName }.distinct().size
    }

    private fun rows(
        sessions: List<ChatSessionRow>,
        selectedSessionKey: String?,
        unreadCountForSession: (String) -> Int,
        onSelectSession: (String) -> Unit
    ): List<AnchoredPicker.Row> {
        return sessions.map { session ->
            val label = ChatPresentationHelpers.sessionLabel(session)
            AnchoredPicker.Row(
                id = "session:${session.key}",
                label = label.take(40),
                sublabel = sublabel(session),
                iconRes = R.drawable.ic_notification_bubble,
                badgeCount = unreadCountForSession(session.key),
                selected = session.key == selectedSessionKey,
                onSelect = { onSelectSession(session.key) }
            )
        }
    }

    private fun sublabel(session: ChatSessionRow): String? {
        return ChatPresentationHelpers.sessionSourceSublabel(session)
    }
}
