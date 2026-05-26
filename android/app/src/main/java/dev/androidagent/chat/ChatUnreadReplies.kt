package dev.androidagent.chat

data class ChatReplySource(
    val sessionId: String? = null,
    val sessionLabel: String? = null,
    val sessionDisplayName: String? = null,
    val harnessId: String? = null,
    val harnessLabel: String? = null,
    val model: String? = null
) {
    fun displayNameFor(sessionKey: String): String {
        return sessionDisplayName ?: sessionLabel ?: sessionId ?: sessionKey.substringAfterLast(":")
    }
}

data class ChatUnreadReply(
    val count: Int = 0,
    val runIds: Set<String> = emptySet(),
    val latestRunId: String? = null,
    val latestPreview: String? = null,
    val latestStatus: String? = null,
    val source: ChatReplySource = ChatReplySource(),
    val receivedAt: Long = 0L
) {
    fun displayNameFor(sessionKey: String): String {
        return source.displayNameFor(sessionKey)
    }
}

fun ChatState.unreadCountForHarness(harnessId: String?): Int {
    val normalized = ChatModelCatalog.normalizeHarnessId(harnessId) ?: return 0
    return unreadReplies.entries.sumOf { (sessionKey, unread) ->
        val sourceHarnessId = ChatModelCatalog.normalizeHarnessId(unread.source.harnessId)
            ?: ChatModelCatalog.harnessFromSessionKey(sessionKey)
        if (sourceHarnessId == normalized) unread.count else 0
    }
}

fun ChatState.latestUnreadSessionKey(): String? {
    return unreadReplies.maxWithOrNull(
        compareBy<Map.Entry<String, ChatUnreadReply>> { it.value.receivedAt }
            .thenBy { it.value.latestRunId.orEmpty() }
    )?.key
}
