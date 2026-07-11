package dev.androidagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.androidagent.AgentConfig
import dev.androidagent.AgentConfigStore
import dev.androidagent.AppShellActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

enum class VoiceRuntimeStatus(val label: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    ERROR("Error")
}

data class VoiceRuntimeState(
    val status: VoiceRuntimeStatus = VoiceRuntimeStatus.IDLE,
    val transcript: String = "",
    val isMuted: Boolean = false,
    val error: String? = null,
    val currentPhoneTask: String? = null,
    val queuedPhoneTasks: Int = 0,
    val latestTaskResult: String? = null,
    val isPhoneTaskRunning: Boolean = false
) {
    val isActive: Boolean = status != VoiceRuntimeStatus.IDLE && status != VoiceRuntimeStatus.ERROR
}

class VoiceRuntimeController internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context?,
    private val sendStart: (voiceSessionId: String, sdp: String, config: AgentConfig) -> Unit,
    private val sendStop: (voiceSessionId: String, reason: String) -> Unit,
    private val onSessionTerminated: (voiceSessionId: String, reason: String) -> Unit,
    private val sendToolCall: (voiceSessionId: String, RealtimeToolCall) -> Unit = { _, _ -> },
    private val onStateChanged: (VoiceRuntimeState) -> Unit,
    private val micPermissionGranted: () -> Boolean,
    private val configProvider: () -> AgentConfig,
    private val openPermissionScreen: () -> Unit,
    private val acquireForegroundLease: () -> Unit,
    private val releaseForegroundLease: () -> Unit,
    private val sessionFactory: (
        onDataChannelEvent: (String) -> Unit,
        onConnectionState: (String) -> Unit
    ) -> RealtimeVoiceSession,
    private val scope: CoroutineScope,
    private val voiceSessionIdFactory: () -> String
) {
    constructor(
        context: Context,
        sendStart: (voiceSessionId: String, sdp: String, config: AgentConfig) -> Unit,
        sendStop: (voiceSessionId: String, reason: String) -> Unit,
        sendToolCall: (voiceSessionId: String, RealtimeToolCall) -> Unit = { _, _ -> },
        onSessionTerminated: (voiceSessionId: String, reason: String) -> Unit = { _, _ -> },
        acquireForegroundLease: () -> Unit = {},
        releaseForegroundLease: () -> Unit = {},
        onStateChanged: (VoiceRuntimeState) -> Unit
    ) : this(
        context = context,
        sendStart = sendStart,
        sendStop = sendStop,
        onSessionTerminated = onSessionTerminated,
        sendToolCall = sendToolCall,
        onStateChanged = onStateChanged,
        micPermissionGranted = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        configProvider = { AgentConfigStore.load(context) },
        openPermissionScreen = {
            context.startActivity(
                Intent(context, AppShellActivity::class.java)
                    .putExtra(AppShellActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        },
        acquireForegroundLease = acquireForegroundLease,
        releaseForegroundLease = releaseForegroundLease,
        sessionFactory = { onEvent, onConnection -> RealtimeWebRtcSession(context, onEvent, onConnection) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        voiceSessionIdFactory = { UUID.randomUUID().toString() }
    )

    private val transcriptNormalizer = RealtimeTranscriptNormalizer()
    private val toolCallAccumulator = RealtimeToolCallAccumulator()
    private val toolOutputsSent = mutableSetOf<String>()
    private val lifecycle = VoiceSessionLifecycle()
    private var session: OwnedSession? = null
    private var state = VoiceRuntimeState()
    private var activeResponseId: String? = null

    fun start() {
        if (!micPermissionGranted()) {
            updateState(
                VoiceRuntimeState(
                    status = VoiceRuntimeStatus.ERROR,
                    error = "Microphone permission is required for voice mode."
                )
            )
            openPermissionScreen()
            return
        }

        val generation = lifecycle.begin() ?: return
        val voiceSessionId = voiceSessionIdFactory()
        val config = configProvider()
        val nextSession = runCatching {
            sessionFactory(
                { raw -> handleDataChannelEvent(generation, raw) },
                { connectionState -> handleConnectionState(generation, connectionState) }
            )
        }.getOrElse { error ->
            lifecycle.beginStop(generation)
            lifecycle.finishStop(generation, error.message ?: error.toString())
            updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.ERROR, error = error.message ?: error.toString()))
            return
        }
        val owner = OwnedSession(generation, voiceSessionId, nextSession)
        session = owner
        runCatching(acquireForegroundLease).onFailure { error ->
            terminate(generation, sendBackendStop = false, failure = error.message ?: error.toString())
            return
        }
        owner.foregroundLeaseAcquired = true
        transcriptNormalizer.reset()
        toolCallAccumulator.reset()
        toolOutputsSent.clear()
        activeResponseId = null
        updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.CONNECTING))

        scope.launch {
            runCatching {
                val offer = nextSession.createOffer()
                if (!lifecycle.owns(generation)) return@runCatching
                sendStart(voiceSessionId, offer, config)
                updateState(state.copy(status = VoiceRuntimeStatus.CONNECTING, error = "Waiting for realtime answer."))
            }.onFailure { error ->
                terminate(generation, sendBackendStop = false, failure = error.message ?: error.toString())
            }
        }
    }

    fun toggleMute() {
        val muted = !state.isMuted
        session?.resource?.setMuted(muted)
        updateState(state.copy(isMuted = muted))
    }

    fun stopFromUi() {
        session?.generation?.let { terminate(it, sendBackendStop = true, reason = "Stopped from Android voice UI") }
    }

    fun close() {
        scope.cancel()
        session?.generation?.let { terminate(it, sendBackendStop = false) }
    }

    fun onRealtimeSdp(payload: JSONObject) {
        val owner = matchingOwner(payload) ?: return
        val answerSdp = payload.optString("sdp").ifBlank {
            payload.optString("answer").ifBlank { payload.optString("answerSdp") }
        }
        if (answerSdp.isBlank()) {
            showBackendError("Realtime SDP answer was missing.")
            return
        }
        scope.launch {
            if (!lifecycle.owns(owner.generation)) return@launch
            runCatching {
                owner.resource.applyAnswer(answerSdp)
                if (!lifecycle.activate(owner.generation)) return@runCatching
                updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, error = null))
            }.onFailure { error ->
                showBackendError(error.message ?: error.toString(), owner.generation)
            }
        }
    }

    fun onRealtimeTranscriptDelta(payload: JSONObject) {
        val generation = matchingOwner(payload)?.generation ?: return
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            val transcript = transcriptNormalizer.applyEvent("realtime.transcript_delta", payload)
            updateState(
                state.copy(
                    status = statusForTranscript(payload, default = VoiceRuntimeStatus.SPEAKING),
                    transcript = transcript.displayText,
                    error = null
                )
            )
        }
    }

    fun onRealtimeItemAdded(payload: JSONObject) {
        val generation = matchingOwner(payload)?.generation ?: return
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            val transcript = transcriptNormalizer.applyEvent("realtime.item_added", payload)
            updateState(
                state.copy(
                    status = statusForTranscript(payload, default = VoiceRuntimeStatus.LISTENING),
                    transcript = transcript.displayText,
                    error = null
                )
            )
        }
    }

    fun onRealtimeSpeechStarted(payload: JSONObject) {
        val generation = matchingOwner(payload)?.generation ?: return
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            val transcript = transcriptNormalizer.applyEvent("realtime.speech_started", payload)
            updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, transcript = transcript.displayText, error = null))
        }
    }

    fun onRealtimeError(payload: JSONObject) {
        val generation = matchingOwner(payload)?.generation ?: return
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            showBackendError(
                payload.optString("message").ifBlank { payload.optString("error").ifBlank { "Realtime voice failed." } },
                generation
            )
        }
    }

    fun onRealtimeClosed(payload: JSONObject) {
        val generation = matchingOwner(payload)?.generation ?: return
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            val reason = payload.optString("reason").ifBlank { "Realtime voice closed." }
            terminate(generation, sendBackendStop = false, idleMessage = reason)
        }
    }

    fun onRealtimeToolResult(payload: JSONObject) {
        val owner = matchingOwner(payload) ?: return
        scope.launch {
            if (!lifecycle.owns(owner.generation)) return@launch
            val callId = payload.optString("callId").ifBlank { payload.optString("call_id") }
            if (callId.isBlank() || !toolOutputsSent.add(callId)) {
                return@launch
            }
            val events = buildRealtimeToolOutputEvents(payload)
            val sentOutput = owner.resource.sendJsonEvent(events[0])
            if (sentOutput) {
                events.drop(1).forEach { owner.resource.sendJsonEvent(it) }
                val nextStatus = if (payload.optBoolean("createResponse", true)) {
                    VoiceRuntimeStatus.THINKING
                } else {
                    VoiceRuntimeStatus.LISTENING
                }
                updateState(state.copy(status = nextStatus, error = null, latestTaskResult = taskResultSummary(payload)))
            } else {
                updateState(state.copy(error = "Could not send realtime tool output."))
            }
        }
    }

    fun onRealtimeTaskStatus(payload: JSONObject) {
        val owner = matchingOwner(payload) ?: return
        scope.launch {
            if (!lifecycle.owns(owner.generation)) return@launch
            val currentTask = payload.optString("currentTask").ifBlank { null }
            updateState(
                state.copy(
                    currentPhoneTask = currentTask,
                    queuedPhoneTasks = payload.optInt("queued", 0).coerceAtLeast(0),
                    isPhoneTaskRunning = payload.optBoolean("running", false),
                    error = null
                )
            )
        }
    }

    private fun handleDataChannelEvent(generation: Long, raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = event.optString("type")
        if (type.isBlank()) {
            return
        }
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            toolCallAccumulator.apply(event)?.let { call ->
                val owner = session?.takeIf { it.generation == generation } ?: return@let
                sendToolCall(owner.voiceSessionId, call)
                updateState(
                    state.copy(
                        status = VoiceRuntimeStatus.THINKING,
                        currentPhoneTask = call.arguments.optString("instruction").takeIf { it.isNotBlank() } ?: state.currentPhoneTask,
                        isPhoneTaskRunning = true,
                        error = null
                    )
                )
                return@launch
            }
            trackResponseState(type, event)
            when {
                type.contains("error") -> handleRealtimeDataChannelError(event)
                type == "input_audio_buffer.speech_started" -> {
                    cancelActiveResponseForBargeIn()
                    val transcript = transcriptNormalizer.applyEvent(type, event)
                    updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, transcript = transcript.displayText, error = null))
                }
                type.contains("transcript") || type == "conversation.item.created" || type.startsWith("response.output_text.") -> {
                    val transcript = transcriptNormalizer.applyEvent(type, event)
                    updateState(
                        state.copy(
                            status = statusForTranscript(event, default = statusForDataChannel(type)),
                            transcript = transcript.displayText,
                            error = null
                        )
                    )
                }
            }
        }
    }

    private fun handleRealtimeDataChannelError(event: JSONObject) {
        val message = realtimeErrorMessage(event)
        if (isBenignResponseCancelRace(message)) {
            activeResponseId = null
            updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, error = null))
            return
        }
        showBackendError(message)
    }

    private fun realtimeErrorMessage(event: JSONObject): String {
        event.optString("message").takeIf { it.isNotBlank() }?.let { return it }
        val error = event.optJSONObject("error")
        error?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
        error?.optString("code")?.takeIf { it.isNotBlank() }?.let { return it }
        event.optString("error").takeIf { it.isNotBlank() }?.let { return it }
        return "Realtime voice failed."
    }

    private fun isBenignResponseCancelRace(message: String): Boolean {
        val normalized = message.lowercase()
        return ("response.cancel" in normalized || "cancellation failed" in normalized) &&
            ("no active response" in normalized || "not active" in normalized)
    }

    private fun trackResponseState(type: String, event: JSONObject) {
        when (type) {
            "response.created" -> {
                activeResponseId = event.optJSONObject("response")?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: event.optString("response_id").takeIf { it.isNotBlank() }
            }
            "response.done" -> {
                val responseId = event.optJSONObject("response")?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: event.optString("response_id").takeIf { it.isNotBlank() }
                if (responseId == null || responseId == activeResponseId) {
                    activeResponseId = null
                }
            }
        }
    }

    private fun cancelActiveResponseForBargeIn() {
        val responseId = activeResponseId ?: return
        session?.resource?.sendJsonEvent(
            JSONObject()
                .put("type", "response.cancel")
                .put("response_id", responseId)
        )
        session?.resource?.sendJsonEvent(JSONObject().put("type", "output_audio_buffer.clear"))
        activeResponseId = null
    }

    private fun handleConnectionState(generation: Long, connectionState: String) {
        scope.launch {
            if (!lifecycle.owns(generation)) return@launch
            when (connectionState) {
                "connected", "completed" -> {
                    lifecycle.activate(generation)
                    updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, error = null))
                }
                "failed", "closed", "disconnected" -> terminate(
                    generation,
                    sendBackendStop = connectionState != "closed",
                    failure = "WebRTC connection $connectionState."
                )
            }
        }
    }

    private fun statusForTranscript(payload: JSONObject, default: VoiceRuntimeStatus): VoiceRuntimeStatus {
        return when (payload.optString("role").lowercase()) {
            "user" -> VoiceRuntimeStatus.THINKING
            "assistant" -> VoiceRuntimeStatus.SPEAKING
            else -> default
        }
    }

    private fun statusForDataChannel(type: String): VoiceRuntimeStatus {
        return if (type.startsWith("conversation.item.input_audio")) {
            VoiceRuntimeStatus.THINKING
        } else {
            VoiceRuntimeStatus.SPEAKING
        }
    }

    private fun taskResultSummary(payload: JSONObject): String {
        val status = payload.optString("status").ifBlank { if (payload.optBoolean("ok", false)) "completed" else "failed" }
        val detail = payload.optString("error").ifBlank { payload.optString("output") }
        return if (detail.isBlank()) {
            "Task $status."
        } else {
            "Task $status: $detail"
        }
    }

    private fun showBackendError(message: String, generation: Long? = session?.generation) {
        generation?.let { terminate(it, sendBackendStop = false, failure = message) }
            ?: updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.ERROR, transcript = state.transcript, error = message))
    }

    private fun terminate(
        generation: Long,
        sendBackendStop: Boolean,
        reason: String = "Voice session stopped",
        failure: String? = null,
        idleMessage: String? = null
    ) {
        if (!lifecycle.beginStop(generation)) return
        val transcript = state.transcript
        val owner = session?.takeIf { it.generation == generation }
        owner?.resource?.close()
        if (session?.generation == generation) session = null
        activeResponseId = null
        if (sendBackendStop && owner != null) sendStop(owner.voiceSessionId, reason)
        if (owner != null) onSessionTerminated(owner.voiceSessionId, reason)
        if (owner?.foregroundLeaseAcquired == true) releaseForegroundLease()
        lifecycle.finishStop(generation, failure)
        updateState(
            if (failure != null) VoiceRuntimeState(status = VoiceRuntimeStatus.ERROR, transcript = transcript, error = failure)
            else VoiceRuntimeState(transcript = transcript, error = idleMessage)
        )
    }

    private fun updateState(next: VoiceRuntimeState) {
        state = next
        onStateChanged(next)
    }

    private data class OwnedSession(
        val generation: Long,
        val voiceSessionId: String,
        val resource: RealtimeVoiceSession,
        var foregroundLeaseAcquired: Boolean = false
    )

    private fun matchingOwner(payload: JSONObject): OwnedSession? {
        val voiceSessionId = payload.optString("voiceSessionId")
        return session?.takeIf { voiceSessionId.isNotBlank() && it.voiceSessionId == voiceSessionId }
    }
}
