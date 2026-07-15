package dev.androidagent.agentchat

import dev.androidagent.AgentConfig
import dev.androidagent.chat.ChatAttachmentPreview
import dev.androidagent.localmodel.LocalChatMessage
import dev.androidagent.localmodel.LocalChatSession
import dev.androidagent.localmodel.LocalChatSessionRepository
import dev.androidagent.localmodel.LocalModelRuntimeRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalAgentTurnCoordinatorConfigurationTest {
    @Test
    fun openReportsUnsupportedModelWithoutThrowing() {
        val fixture = fixture("/data/local/tmp/model.bin")

        assertFalse(fixture.coordinator.open(null))

        assertEquals(listOf("chat.error", "chat.state"), fixture.messages.map { it.getString("type") })
        assertEquals("error", fixture.statuses.single().second)
        assertEquals(0, fixture.store.appendCount)
        assertEquals(0, fixture.runner.calls)
    }

    @Test
    fun startTurnRejectsUnsupportedModelBeforePersistingUserMessage() {
        val fixture = fixture("/data/local/tmp/model.bin")

        assertFalse(fixture.coordinator.startTurn(LocalTurnRequest("hello")))

        assertEquals(listOf("chat.error", "chat.state"), fixture.messages.map { it.getString("type") })
        assertEquals("error", fixture.statuses.single().second)
        assertEquals(0, fixture.store.appendCount)
        assertEquals(0, fixture.runner.calls)
    }

    private fun fixture(path: String): Fixture {
        val messages = mutableListOf<JSONObject>()
        val statuses = mutableListOf<Pair<String, String>>()
        val store = RecordingSessionStore()
        val runner = RecordingRunner()
        val runtime = LocalModelRuntimeRouter(
            liteRtFactory = { error("Unsupported paths must not initialize LiteRT-LM") },
            ggufFactory = { error("Unsupported paths must not initialize GGUF") }
        )
        val coordinator = LocalAgentTurnCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            configProvider = { config(path) },
            onStatus = { message, state -> statuses += message to state },
            onChatMessage = messages::add,
            store = store,
            runtimeProfile = runtime::profile,
            toolDescriptions = { JSONArray() },
            runner = runner
        )
        return Fixture(coordinator, messages, statuses, store, runner)
    }

    private fun config(path: String) = AgentConfig(
        hostUrl = "ws://127.0.0.1:8788/phone",
        deviceId = "phone",
        token = "token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "local-litertlm",
        reasoningEffort = "medium",
        localModelPath = path
    )

    private data class Fixture(
        val coordinator: LocalAgentTurnCoordinator,
        val messages: List<JSONObject>,
        val statuses: List<Pair<String, String>>,
        val store: RecordingSessionStore,
        val runner: RecordingRunner
    )

    private class RecordingRunner : LocalTurnRunner {
        var calls = 0

        override suspend fun run(
            sessionKey: String,
            runId: String,
            userText: String,
            history: List<LocalChatMessage>,
            imagePaths: List<String>
        ): String {
            calls += 1
            return "done"
        }
    }

    private class RecordingSessionStore : LocalChatSessionRepository {
        private var selected = LocalChatSession("local:default", "Local chat", 1, emptyList())
        var appendCount = 0

        override fun all(): List<LocalChatSession> = listOf(selected)

        override fun session(key: String?): LocalChatSession = selected

        override fun create(label: String?): LocalChatSession = selected

        override fun append(
            sessionKey: String,
            role: String,
            text: String,
            id: String,
            attachments: List<ChatAttachmentPreview>
        ): LocalChatSession {
            appendCount += 1
            return selected
        }
    }
}
