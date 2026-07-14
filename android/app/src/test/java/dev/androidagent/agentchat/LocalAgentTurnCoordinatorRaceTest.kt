package dev.androidagent.agentchat

import dev.androidagent.AgentConfig
import dev.androidagent.chat.ChatAttachmentPreview
import dev.androidagent.localmodel.LocalChatMessage
import dev.androidagent.localmodel.LocalChatSession
import dev.androidagent.localmodel.LocalChatSessionRepository
import dev.androidagent.localmodel.LocalModelRuntimeKind
import dev.androidagent.localmodel.LocalModelRuntimeProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class LocalAgentTurnCoordinatorRaceTest {
    @Test
    fun stopThenImmediateStartKeepsNewGenerationOwnedAfterOldCancellation() = runBlocking {
        val runner = SlowCancellationRunner()
        val store = FakeSessionStore()
        val messages = Collections.synchronizedList(mutableListOf<JSONObject>())
        val secondCompleted = CompletableDeferred<LocalTurnOutcome>()
        val coordinator = coordinator(store, runner, messages)

        assertTrue(coordinator.startTurn(LocalTurnRequest("first")))
        runner.firstStarted.await()
        coordinator.stop(reason = "Stop requested")
        runner.firstCancelling.await()
        assertTrue(coordinator.startTurn(LocalTurnRequest("second", onCompleted = secondCompleted::complete)))
        assertFalse(coordinator.startTurn(LocalTurnRequest("third")))

        runner.releaseFirst.complete(Unit)
        assertEquals("second complete", secondCompleted.await().text)

        val replies = messages.filter { it.optString("type") == "chat.reply_available" }
        assertEquals(listOf("failed", "completed"), replies.map { it.optString("status") })
        assertEquals("Stopped local model turn", replies[0].optString("textPreview"))
        assertEquals("second complete", replies[1].optString("textPreview"))
        assertEquals(1, messages.count {
            it.optString("type") == "chat.state" && it.optString("status") == "Stop requested"
        })
    }

    @Test
    fun newSessionWaitsForCancelledGenerationBeforeReplacingSession() = runBlocking {
        val runner = SlowCancellationRunner()
        val store = FakeSessionStore()
        val coordinator = coordinator(store, runner, mutableListOf())

        assertTrue(coordinator.startTurn(LocalTurnRequest("first")))
        runner.firstStarted.await()
        coordinator.newSession("replacement")
        runner.firstCancelling.await()
        assertEquals(0, store.createdCount)

        runner.releaseFirst.complete(Unit)
        store.sessionCreated.await()
        assertEquals(1, store.createdCount)
        assertEquals("replacement", store.session(null).label)
    }

    @Test
    fun closeCancelsGenerationAndRejectsLaterTurns() = runBlocking {
        val runner = SlowCancellationRunner()
        val coordinator = coordinator(FakeSessionStore(), runner, mutableListOf())

        assertTrue(coordinator.startTurn(LocalTurnRequest("first")))
        runner.firstStarted.await()
        coordinator.close()
        runner.firstCancelling.await()

        assertFalse(coordinator.startTurn(LocalTurnRequest("late")))
        runner.releaseFirst.complete(Unit)
        Unit
    }

    @Test
    fun stopAndJoinDoesNotReturnUntilCancelledGenerationFinishesCleanup() = runBlocking {
        val runner = SlowCancellationRunner()
        val coordinator = coordinator(FakeSessionStore(), runner, mutableListOf())
        assertTrue(coordinator.startTurn(LocalTurnRequest("first")))
        runner.firstStarted.await()

        val stopping = async { coordinator.stopAndJoin(reason = "Voice ended") }
        runner.firstCancelling.await()
        assertFalse(stopping.isCompleted)
        runner.releaseFirst.complete(Unit)
        stopping.await()
        assertTrue(stopping.isCompleted)
    }

    private fun coordinator(
        store: FakeSessionStore,
        runner: LocalTurnRunner,
        messages: MutableList<JSONObject>
    ) = LocalAgentTurnCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = ::config,
        onStatus = { _, _ -> },
        onChatMessage = messages::add,
        store = store,
        runtimeProfile = { LocalModelRuntimeProfile(LocalModelRuntimeKind.LiteRtLm, 4096, true) },
        toolDescriptions = { JSONArray() },
        runner = runner
    )

    private fun config() = AgentConfig(
        hostUrl = "ws://127.0.0.1:8788/phone",
        deviceId = "phone",
        token = "token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "local-litertlm",
        reasoningEffort = "medium"
    )

    private class SlowCancellationRunner : LocalTurnRunner {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelling = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        private val permit = Mutex()
        private var calls = 0

        override suspend fun run(
            sessionKey: String,
            runId: String,
            userText: String,
            history: List<LocalChatMessage>,
            imagePaths: List<String>
        ): String = permit.withLock {
            calls += 1
            if (calls == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        firstCancelling.complete(Unit)
                        releaseFirst.await()
                    }
                }
            }
            "second complete"
        }
    }

    private class FakeSessionStore : LocalChatSessionRepository {
        private var selected = LocalChatSession("local:default", "Local chat", 1, emptyList())
        var createdCount = 0
            private set
        val sessionCreated = CompletableDeferred<Unit>()

        override fun all(): List<LocalChatSession> = listOf(selected)

        override fun session(key: String?): LocalChatSession = selected

        override fun create(label: String?): LocalChatSession {
            createdCount += 1
            selected = LocalChatSession("local:$createdCount", label ?: "Local chat", System.currentTimeMillis(), emptyList())
            sessionCreated.complete(Unit)
            return selected
        }

        override fun append(
            sessionKey: String,
            role: String,
            text: String,
            id: String,
            attachments: List<ChatAttachmentPreview>
        ): LocalChatSession {
            val message = LocalChatMessage(id, role, text, System.currentTimeMillis(), attachments)
            selected = selected.copy(key = sessionKey, updatedAt = message.timestamp, messages = selected.messages + message)
            return selected
        }
    }
}
