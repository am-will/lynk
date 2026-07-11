package dev.androidagent.voice

import dev.androidagent.AgentConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRuntimeControllerLifecycleTest {
    @Test
    fun failureCleansExactlyOnceAndLateCallbacksCannotMutateRetry() {
        val sessions = mutableListOf<FakeSession>()
        val connectionCallbacks = mutableListOf<(String) -> Unit>()
        val states = mutableListOf<VoiceRuntimeState>()
        var foregroundAcquires = 0
        var foregroundReleases = 0
        var backendStops = 0
        var localStops = 0
        val voiceIds = ArrayDeque(listOf(VOICE_A, VOICE_B))

        val controller = VoiceRuntimeController(
            context = null,
            sendStart = { _, _, _ -> },
            sendStop = { _, _ -> backendStops += 1 },
            onSessionTerminated = { _, _ -> localStops += 1 },
            onStateChanged = { state -> states.add(state) },
            micPermissionGranted = { true },
            configProvider = ::config,
            openPermissionScreen = {},
            acquireForegroundLease = { foregroundAcquires += 1 },
            releaseForegroundLease = { foregroundReleases += 1 },
            sessionFactory = { _, onConnection ->
                connectionCallbacks += onConnection
                FakeSession().also(sessions::add)
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            voiceSessionIdFactory = { voiceIds.removeFirst() }
        )

        controller.start()
        connectionCallbacks[0]("failed")
        connectionCallbacks[0]("failed")
        assertEquals(1, sessions[0].closeCount)
        assertEquals(1, foregroundReleases)
        assertEquals(1, backendStops)
        assertEquals(VoiceRuntimeStatus.ERROR, states.last().status)
        val terminalStateCount = states.size
        controller.onRealtimeTranscriptDelta(JSONObject().put("delta", "late"))
        assertEquals(terminalStateCount, states.size)

        controller.start()
        controller.onRealtimeSdp(JSONObject().put("voiceSessionId", VOICE_A).put("sdp", "old-answer"))
        assertEquals(0, sessions[1].answerCount)
        controller.onRealtimeSdp(JSONObject().put("voiceSessionId", VOICE_B).put("sdp", "new-answer"))
        assertEquals(1, sessions[1].answerCount)
        controller.onRealtimeTranscriptDelta(JSONObject().put("voiceSessionId", VOICE_A).put("role", "assistant").put("itemId", "assistant-old").put("delta", "late"))
        assertEquals(VoiceRuntimeStatus.LISTENING, states.last().status)
        controller.onRealtimeTranscriptDelta(JSONObject().put("voiceSessionId", VOICE_B).put("role", "assistant").put("itemId", "assistant-new").put("delta", "current"))
        assertEquals("Codex: current", states.last().transcript)
        controller.onRealtimeToolResult(JSONObject().put("voiceSessionId", VOICE_A).put("callId", "same").put("ok", true).put("status", "completed"))
        assertEquals(0, sessions[1].sentEventCount)
        controller.onRealtimeToolResult(JSONObject().put("voiceSessionId", VOICE_B).put("callId", "same").put("ok", true).put("status", "completed"))
        assertEquals(2, sessions[1].sentEventCount)
        val stateBeforeStaleConnection = states.last()
        connectionCallbacks[0]("connected")
        assertEquals(stateBeforeStaleConnection, states.last())
        connectionCallbacks[1]("connected")
        assertEquals(VoiceRuntimeStatus.LISTENING, states.last().status)
        controller.close()
        connectionCallbacks[1]("failed")

        assertEquals(2, foregroundAcquires)
        assertEquals(2, foregroundReleases)
        assertEquals(2, localStops)
        assertEquals(1, sessions[1].closeCount)
        assertEquals(VoiceRuntimeStatus.IDLE, states.last().status)
    }

    private class FakeSession : RealtimeVoiceSession {
        var closeCount = 0
        var answerCount = 0
        var sentEventCount = 0

        override suspend fun createOffer(): String = "offer"
        override suspend fun applyAnswer(answerSdp: String) { answerCount += 1 }
        override fun setMuted(muted: Boolean) = Unit
        override fun sendJsonEvent(event: JSONObject): Boolean {
            sentEventCount += 1
            return true
        }
        override fun close() {
            closeCount += 1
        }
    }

    private fun config() = AgentConfig(
        hostUrl = "http://127.0.0.1",
        deviceId = "test",
        token = "test-token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "test-model",
        reasoningEffort = "medium"
    )

    companion object {
        private const val VOICE_A = "11111111-1111-4111-8111-111111111111"
        private const val VOICE_B = "22222222-2222-4222-8222-222222222222"
    }
}
