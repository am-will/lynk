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

        val controller = VoiceRuntimeController(
            context = null,
            sendStart = { _, _ -> },
            sendStop = { backendStops += 1 },
            onSessionTerminated = { localStops += 1 },
            onStateChanged = states::add,
            micPermissionGranted = { true },
            configProvider = ::config,
            openPermissionScreen = {},
            acquireForegroundLease = { foregroundAcquires += 1 },
            releaseForegroundLease = { foregroundReleases += 1 },
            sessionFactory = { _, onConnection ->
                connectionCallbacks += onConnection
                FakeSession().also(sessions::add)
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

        controller.start()
        connectionCallbacks[0]("failed")
        connectionCallbacks[0]("failed")
        assertEquals(1, sessions[0].closeCount)
        assertEquals(1, foregroundReleases)
        assertEquals(1, backendStops)
        assertEquals(VoiceRuntimeStatus.ERROR, states.last().status)

        controller.start()
        connectionCallbacks[0]("connected")
        assertEquals(VoiceRuntimeStatus.CONNECTING, states.last().status)
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

        override suspend fun createOffer(): String = "offer"
        override suspend fun applyAnswer(answerSdp: String) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun sendJsonEvent(event: JSONObject): Boolean = true
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
}
