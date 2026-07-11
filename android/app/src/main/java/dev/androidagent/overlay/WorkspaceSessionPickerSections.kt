package dev.androidagent.overlay

import dev.androidagent.HostWorkspacePaths
import dev.androidagent.R
import dev.androidagent.chat.ChatModelCatalog
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.ui.AnchoredPicker

object WorkspaceSessionPickerSections {
    const val QUICK_CHATS_ROW_ID = "quick-chats"

    fun build(
        sessions: List<ChatSessionRow>,
        selectedSessionKey: String?,
        activeWorkspacePath: String? = null,
        expandedWorkspaceKeys: Set<String>,
        expandedQuickChats: Boolean,
        unreadCountForSession: (String) -> Int,
        onToggleWorkspace: (String) -> Unit,
        onToggleQuickChats: () -> Unit,
        onSelectSession: (String) -> Unit,
        limit: Int = Int.MAX_VALUE
    ): List<AnchoredPicker.Section> {
        val scoped = sessions.take(limit)
        val workspaceSessions = scoped.filter { !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank() }
        val quickChatSessions = scoped.filter { it.workspacePath.isNullOrBlank() && it.workspaceName.isNullOrBlank() }
        val sections = mutableListOf<AnchoredPicker.Section>()
        val activeWorkspaceKey = activeWorkspaceKey(
            sessions = workspaceSessions,
            selectedSessionKey = selectedSessionKey,
            activeWorkspacePath = activeWorkspacePath
        )
        val activeQuickChats = quickChatSessions.any { it.key == selectedSessionKey }

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
                        id = workspaceRowId(group.key),
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
            val sortedQuickChats = quickChatSessions.sortedByRecency()
            val rows = mutableListOf(
                AnchoredPicker.Row(
                    id = QUICK_CHATS_ROW_ID,
                    label = "QuickChats",
                    sublabel = quickChatsSublabel(activeQuickChats, sortedQuickChats.size),
                    selectable = false,
                    emphasizeLabel = activeQuickChats,
                    emphasizeSublabel = activeQuickChats,
                    trailingIconRes = R.drawable.ic_chevron_right,
                    trailingIconRotation = if (expandedQuickChats) 90f else 0f,
                    dismissOnSelect = false,
                    onSelect = onToggleQuickChats
                )
            )
            if (expandedQuickChats) {
                rows.addAll(rows(
                    sessions = sortedQuickChats,
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

        return sections
    }

    fun forHarness(sessions: List<ChatSessionRow>, harnessId: String?): List<ChatSessionRow> {
        val activeHarness = ChatModelCatalog.normalizeHarnessId(harnessId) ?: return sessions
        return sessions.filter { session ->
            val sessionHarness = ChatModelCatalog.normalizeHarnessId(session.harnessId)
                ?: ChatModelCatalog.harnessFromSessionKey(session.key)
            sessionHarness == activeHarness
        }
    }

    fun workspaceCount(sessions: List<ChatSessionRow>): Int {
        return sessions
            .filter { !it.workspacePath.isNullOrBlank() || !it.workspaceName.isNullOrBlank() }
            .map(::workspaceKey)
            .distinct()
            .size
    }

    fun activeWorkspaceKey(
        sessions: List<ChatSessionRow>,
        selectedSessionKey: String?,
        activeWorkspacePath: String?
    ): String? {
        selectedSessionKey?.takeIf { it.isNotBlank() }?.let { sessionKey ->
            sessions.firstOrNull { it.key == sessionKey }?.takeIf(::hasWorkspace)?.let(::workspaceKey)?.let {
                return it
            }
        }

        val activeWorkspaceCandidates = workspaceCandidates(activeWorkspacePath).takeIf { it.isNotEmpty() } ?: return null
        return sessions.firstOrNull { session ->
            hasWorkspace(session) && workspaceCandidates(workspaceKey(session)).any { it in activeWorkspaceCandidates }
        }?.let(::workspaceKey)
    }

    fun isQuickChatSession(sessions: List<ChatSessionRow>, sessionKey: String?): Boolean {
        if (sessionKey.isNullOrBlank()) return false
        return sessions.firstOrNull { it.key == sessionKey }?.let {
            it.workspacePath.isNullOrBlank() && it.workspaceName.isNullOrBlank()
        } == true
    }

    fun workspaceRowId(workspaceKey: String): String {
        return "workspace:$workspaceKey"
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
            return first?.workspacePath?.let(HostWorkspacePaths::display)
                ?: first?.workspaceName
                ?: HostWorkspacePaths.display(key)
        }
    }

    private fun workspaceKey(session: ChatSessionRow): String {
        return session.workspacePath?.takeIf { it.isNotBlank() }
            ?: session.workspaceName?.takeIf { it.isNotBlank() }
            ?: "Workspace"
    }

    private fun hasWorkspace(session: ChatSessionRow): Boolean {
        return !session.workspacePath.isNullOrBlank() || !session.workspaceName.isNullOrBlank()
    }

    private fun workspaceCandidates(path: String?): Set<String> {
        val trimmed = path?.trim()?.takeIf { it.isNotBlank() } ?: return emptySet()
        return setOf(trimmed, HostWorkspacePaths.display(trimmed))
    }

    private fun workspaceSublabel(active: Boolean, sessionCount: Int): String {
        val count = "$sessionCount session${if (sessionCount == 1) "" else "s"}"
        return if (active) "Active workspace, $count" else count
    }

    private fun quickChatsSublabel(active: Boolean, sessionCount: Int): String {
        val count = "$sessionCount session${if (sessionCount == 1) "" else "s"}"
        return if (active) "Active quick chats, $count" else count
    }

    private fun List<ChatSessionRow>.sortedByRecency(): List<ChatSessionRow> {
        return sortedWith(compareByDescending<ChatSessionRow> { it.updatedAt ?: Long.MIN_VALUE }
            .thenBy { ChatPresentationHelpers.sessionLabel(it) })
    }
}
