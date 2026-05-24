package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatSessionCoordinatorTest {
    @Test
    fun ignoresHistoryForPreviousSessionDuringNewChat() {
        val coordinator = NewChatSessionCoordinator()
        coordinator.begin(request(previousSessionKey = "old"))

        assertTrue(coordinator.shouldIgnoreHistory(null, null))
        assertTrue(coordinator.shouldIgnoreHistory("old", null))
        assertFalse(coordinator.shouldIgnoreHistory("new", null))
    }

    @Test
    fun tracksWhetherNewChatHistoryLoadedBeforeState() {
        val coordinator = NewChatSessionCoordinator()
        coordinator.begin(request())

        coordinator.markHistoryLoaded("new")

        assertEquals(true, coordinator.completeIfStateLoaded("new"))
        assertFalse(coordinator.pending)
    }

    @Test
    fun consumesWorkspaceRetryAndClearsPendingState() {
        val coordinator = NewChatSessionCoordinator()
        val request = request(workspacePath = "~/missing")
        coordinator.begin(request)

        assertEquals(request, coordinator.consumeWorkspaceNotFoundRetry())
        assertFalse(coordinator.pending)
        assertNull(coordinator.consumeWorkspaceNotFoundRetry())
    }

    private fun request(
        workspacePath: String? = null,
        previousSessionKey: String? = null
    ): PendingNewChatRequest {
        return PendingNewChatRequest(
            selectedModel = "codex:gpt-5.3-codex",
            route = ChatClientRoute.Host,
            model = "gpt-5.3-codex",
            workspacePath = workspacePath,
            previousSessionKey = previousSessionKey
        )
    }
}
