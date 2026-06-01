package dev.androidagent.overlay

import dev.androidagent.CodexWorkspacePaths
import dev.androidagent.R
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.ui.AnchoredPicker

object CodexSessionPickerSections {
    fun build(
        sessions: List<ChatSessionRow>,
        selectedSessionKey: String?,
        expandedWorkspaceKeys: Set<String>,
        unreadCountForSession: (String) -> Int,
        onToggleWorkspace: (String) -> Unit,
        onSelectSession: (String) -> Unit,
        limit: Int = Int.MAX_VALUE
    ): List<AnchoredPicker.Section> {
        val scoped = sessions.take(limit)
        val workspaceSessions = scoped.filter { !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank() }
        val quickChatSessions = scoped.filter { it.workspacePath.isNullOrBlank() && it.workspaceName.isNullOrBlank() }
        val sections = mutableListOf<AnchoredPicker.Section>()
        val activeWorkspaceKey = workspaceSessions
            .firstOrNull { it.key == selectedSessionKey }
            ?.let(::workspaceKey)

        workspaceSessions
            .groupBy(::workspaceKey)
            .map { (workspaceKey, groupedSessions) ->
                WorkspaceGroup(
                    key = workspaceKey,
                    sessions = groupedSessions.sortedByRecency(),
                    mostRecentUpdatedAt = groupedSessions.maxOfOrNull { it.updatedAt ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
                )
            }
            .sortedWith(compareByDescending<WorkspaceGroup> { it.mostRecentUpdatedAt }.thenBy { it.title() })
            .forEach { group ->
                val expanded = group.key in expandedWorkspaceKeys
                val active = group.key == activeWorkspaceKey
                val rows = mutableListOf(
                    AnchoredPicker.Row(
                        id = "workspace:${group.key}",
                        label = group.title(),
                        sublabel = workspaceSublabel(active, group.sessions.size),
                        selectable = false,
                        emphasizeLabel = active,
                        emphasizeSublabel = active,
                        trailingIconRes = R.drawable.ic_chevron_right,
                        trailingIconRotation = if (expanded) 90f else 0f,
                        dismissOnSelect = false,
                        onSelect = { onToggleWorkspace(group.key) }
                    )
                )
                if (expanded) {
                    rows.addAll(rows(
                        sessions = group.sessions,
                        selectedSessionKey = selectedSessionKey,
                        unreadCountForSession = unreadCountForSession,
                        onSelectSession = onSelectSession
                    ))
                }
                sections.add(AnchoredPicker.Section(
                    null,
                    rows
                ))
            }

        if (quickChatSessions.isNotEmpty()) {
            sections.add(AnchoredPicker.Section(
                "QuickChats",
                rows(
                    sessions = quickChatSessions.sortedByRecency(),
                    selectedSessionKey = selectedSessionKey,
                    unreadCountForSession = unreadCountForSession,
                    onSelectSession = onSelectSession
                )
            ))
        }

        return sections
    }

    fun workspaceCount(sessions: List<ChatSessionRow>): Int {
        return sessions
            .filter { !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank() }
            .map(::workspaceKey)
            .distinct()
            .size
    }

    fun workspaceKeyForSession(sessions: List<ChatSessionRow>, sessionKey: String?): String? {
        if (sessionKey.isNullOrBlank()) return null
        return sessions.firstOrNull { it.key == sessionKey }?.takeIf {
            !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank()
        }?.let(::workspaceKey)
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

    private data class WorkspaceGroup(
        val key: String,
        val sessions: List<ChatSessionRow>,
        val mostRecentUpdatedAt: Long
    ) {
        fun title(): String {
            val first = sessions.firstOrNull()
            return first?.workspacePath?.let(CodexWorkspacePaths::display)
                ?: first?.workspaceName
                ?: CodexWorkspacePaths.display(key)
        }
    }

    private fun workspaceKey(session: ChatSessionRow): String {
        return session.workspacePath?.takeIf { it.isNotBlank() }
            ?: session.workspaceName?.takeIf { it.isNotBlank() }
            ?: "Workspace"
    }

    private fun workspaceSublabel(active: Boolean, sessionCount: Int): String {
        val count = "$sessionCount session${if (sessionCount == 1) "" else "s"}"
        return if (active) "Active workspace, $count" else count
    }

    private fun List<ChatSessionRow>.sortedByRecency(): List<ChatSessionRow> {
        return sortedWith(compareByDescending<ChatSessionRow> { it.updatedAt ?: Long.MIN_VALUE }
            .thenBy { ChatPresentationHelpers.sessionLabel(it) })
    }
}
