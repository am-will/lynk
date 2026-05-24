package dev.androidagent

internal enum class ChatClientRoute {
    Host,
    Local
}

internal data class PendingNewChatRequest(
    val selectedModel: String,
    val route: ChatClientRoute,
    val model: String,
    val workspacePath: String?,
    val previousSessionKey: String?
)

internal class NewChatSessionCoordinator {
    var pending: Boolean = false
        private set
    private var previousSessionKey: String? = null
    private var historySessionKey: String? = null
    private var request: PendingNewChatRequest? = null
    private var workspacePromptActive = false

    fun begin(request: PendingNewChatRequest) {
        previousSessionKey = request.previousSessionKey
        historySessionKey = null
        this.request = request
        pending = true
    }

    fun shouldIgnoreHistory(incomingSessionKey: String?, activeSessionKey: String?): Boolean {
        if (!pending) return false
        return incomingSessionKey == null ||
            incomingSessionKey == previousSessionKey ||
            (!activeSessionKey.isNullOrBlank() && incomingSessionKey != activeSessionKey)
    }

    fun markHistoryLoaded(sessionKey: String?) {
        if (pending) {
            historySessionKey = sessionKey
        }
    }

    fun completeIfStateLoaded(sessionKey: String?): Boolean? {
        if (!pending || sessionKey.isNullOrBlank()) {
            return null
        }
        val hasLoadedNewHistory = historySessionKey == sessionKey
        clear()
        return hasLoadedNewHistory
    }

    fun consumeWorkspaceNotFoundRetry(): PendingNewChatRequest? {
        if (!pending) {
            return null
        }
        val retry = request
        clear()
        return retry
    }

    fun clear() {
        pending = false
        previousSessionKey = null
        historySessionKey = null
        request = null
    }

    fun startWorkspacePrompt(): Boolean {
        if (workspacePromptActive) {
            return false
        }
        workspacePromptActive = true
        return true
    }

    fun finishWorkspacePrompt() {
        workspacePromptActive = false
    }
}
