package dev.androidagent.net

import dev.androidagent.AgentConfig
import dev.androidagent.AgentLocation
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.androidagent.agentchat.ChatSendDelivery
import dev.androidagent.chat.ChatAttachmentWireEncoder
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.voice.RealtimeToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class BridgeConnectionPhase {
    CONNECTING,
    CONNECTED,
    ERROR
}

data class BridgeConnectionState(
    val phase: BridgeConnectionPhase,
    val message: String
)

class PhoneWebSocketClient(
    private val config: AgentConfig,
    private val commandExecutor: AccessibilityCommandExecutor,
    private val onStatus: (String, String) -> Unit,
    private val onConnectionState: (BridgeConnectionState) -> Unit = {},
    private val onRealtimeSdp: (JSONObject) -> Unit = {},
    private val onRealtimeTranscriptDelta: (JSONObject) -> Unit = {},
    private val onRealtimeItemAdded: (JSONObject) -> Unit = {},
    private val onRealtimeSpeechStarted: (JSONObject) -> Unit = {},
    private val onRealtimeError: (JSONObject) -> Unit = {},
    private val onRealtimeClosed: (JSONObject) -> Unit = {},
    private val onRealtimeToolResult: (JSONObject) -> Unit = {},
    private val onRealtimeTaskStatus: (JSONObject) -> Unit = {},
    private val onChatMessage: (JSONObject) -> Unit = {}
) : WebSocketListener() {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var manuallyClosed = false
    private var reconnectAttempts = 0
    private var connected = false
    private var registered = false
    private var reconnectRunnable: Runnable? = null
    private var registerTimeoutRunnable: Runnable? = null
    private var endpointIndex = 0

    fun connect() {
        if (manuallyClosed) return
        if (socket != null) return
        cancelScheduledReconnect()
        cancelRegisterTimeout()
        manuallyClosed = false
        connected = false
        registered = false
        val hostUrl = activeHostUrl()
        val request = Request.Builder().url(hostUrl).build()
        socket = client.newWebSocket(request, this)
        val statusText = "Connecting to $hostUrl"
        onStatus(statusText, "info")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.CONNECTING, statusText))
    }

    fun close() {
        manuallyClosed = true
        connected = false
        registered = false
        socket?.close(1000, "service stopped")
        socket = null
        cancelScheduledReconnect()
        cancelRegisterTimeout()
        client.dispatcher.executorService.shutdown()
    }

    fun sendUserRequest(text: String, requestConfig: AgentConfig = config, includeSystemPrompt: Boolean = false) {
        val message = JSONObject()
            .put("type", "user_request")
            .put("deviceId", requestConfig.deviceId)
            .put("inputType", "text")
            .put("text", text)
            .put("model", requestConfig.model)
            .put("reasoningEffort", requestConfig.reasoningEffort)
        if (includeSystemPrompt) {
            message.put("systemPrompt", requestConfig.systemPrompt)
        }
        sendJson(message, reportChatError = true)
    }

    fun sendStopRequest(reason: String) {
        val message = JSONObject()
            .put("type", "agent_control")
            .put("deviceId", config.deviceId)
            .put("action", "stop")
            .put("reason", reason)
        sendJson(message, reportChatError = true)
    }

    fun sendChatOpen(sessionKey: String? = null): Boolean {
        val message = JSONObject()
            .put("type", "chat.open")
            .put("deviceId", config.deviceId)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        return sendJson(message)
    }

    fun sendChatMessage(
        text: String,
        sessionKey: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        delivery: ChatSendDelivery = ChatSendDelivery.Normal,
        attachments: List<StoredChatAttachment> = emptyList()
    ): Boolean {
        val message = JSONObject()
            .put("type", "chat.send")
            .put("deviceId", config.deviceId)
            .put("text", text)
            .put("delivery", delivery.key)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        model?.takeIf { it.isNotBlank() }?.let { message.put("model", it) }
        reasoningEffort?.takeIf { it.isNotBlank() }?.let { message.put("reasoningEffort", it) }
        if (attachments.isNotEmpty()) {
            message.put(
                "attachments",
                ChatAttachmentWireEncoder.toJsonArray(attachments)
            )
        }
        return sendJson(message, reportChatError = true)
    }

    fun sendChatStop(sessionKey: String? = null, runId: String? = null, reason: String = "Stopped from Android chat") {
        val message = JSONObject()
            .put("type", "chat.stop")
            .put("deviceId", config.deviceId)
            .put("reason", reason)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        runId?.takeIf { it.isNotBlank() }?.let { message.put("runId", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSelectSession(sessionKey: String) {
        val message = JSONObject()
            .put("type", "chat.select_session")
            .put("deviceId", config.deviceId)
            .put("sessionKey", sessionKey)
        sendJson(message, reportChatError = true)
    }

    fun sendChatNewSession(
        label: String? = null,
        model: String? = null,
        workspacePath: String? = null,
        createWorkspaceIfMissing: Boolean = false
    ) {
        val message = JSONObject()
            .put("type", "chat.new_session")
            .put("deviceId", config.deviceId)
        label?.takeIf { it.isNotBlank() }?.let { message.put("label", it) }
        model?.takeIf { it.isNotBlank() }?.let { message.put("model", it) }
        workspacePath?.takeIf { it.isNotBlank() }?.let { message.put("workspacePath", it) }
        if (createWorkspaceIfMissing) {
            message.put("createWorkspaceIfMissing", true)
        }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSetModel(sessionKey: String?, model: String) {
        val message = JSONObject()
            .put("type", "chat.set_model")
            .put("deviceId", config.deviceId)
            .put("model", model)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSetReasoning(sessionKey: String?, reasoningEffort: String) {
        val message = JSONObject()
            .put("type", "chat.set_reasoning")
            .put("deviceId", config.deviceId)
            .put("reasoningEffort", reasoningEffort)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatControlCommand(command: String, args: JSONObject = JSONObject()) {
        val message = JSONObject()
            .put("type", "chat.control_command")
            .put("deviceId", config.deviceId)
            .put("command", command)
            .put("args", args)
        sendJson(message, reportChatError = true)
    }

    fun sendRealtimeStart(sdp: String, requestConfig: AgentConfig = config, location: AgentLocation? = null) {
        val message = JSONObject()
            .put("type", "realtime.start")
            .put("deviceId", requestConfig.deviceId)
            .put("sdp", sdp)
            .put("systemPrompt", requestConfig.systemPrompt)
            .put("model", requestConfig.model)
            .put("reasoningEffort", requestConfig.reasoningEffort)
        requestConfig.openAiApiKey.takeIf { it.isNotBlank() }?.let { message.put("openAiApiKey", it) }
        location?.let { message.put("location", it.toJson()) }
        val sent = sendJson(message)
        Log.i(TAG, "sendRealtimeStart sent=$sent sdpLength=${sdp.length}")
        if (!sent) {
            dispatchRealtimeError("Phone WebSocket is not connected for realtime voice.")
        }
    }

    fun sendRealtimeStop(reason: String) {
        val message = JSONObject()
            .put("type", "realtime.stop")
            .put("deviceId", config.deviceId)
            .put("reason", reason)
        val sent = sendJson(message)
        Log.i(TAG, "sendRealtimeStop sent=$sent")
        if (!sent) {
            dispatchRealtimeError("Phone WebSocket is not connected for realtime voice.")
        }
    }

    fun sendRealtimeToolCall(call: RealtimeToolCall, requestConfig: AgentConfig = config) {
        val message = JSONObject()
            .put("type", "realtime.tool_call")
            .put("deviceId", requestConfig.deviceId)
            .put("callId", call.callId)
            .put("name", call.name)
            .put("arguments", call.arguments)
            .put("model", requestConfig.model)
            .put("reasoningEffort", requestConfig.reasoningEffort)
        call.itemId?.let { message.put("itemId", it) }
        val sent = sendJson(message)
        Log.i(TAG, "sendRealtimeToolCall sent=$sent callId=${call.callId} name=${call.name}")
        if (!sent) {
            dispatchRealtimeError("Phone WebSocket is not connected for realtime tool calls.")
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        runOnMain { handleOpen(webSocket) }
    }

    private fun handleOpen(webSocket: WebSocket) {
        if (webSocket != socket) {
            webSocket.close(1000, "stale connection")
            return
        }
        cancelScheduledReconnect()
        cancelRegisterTimeout()
        reconnectAttempts = 0
        connected = false
        registered = false
        val register = JSONObject()
            .put("type", "register")
            .put("deviceId", config.deviceId)
            .put("token", config.token)
            .put(
                "capabilities",
                JSONArray(listOf("accessibility_tree", "gestures", "text_input", "screenshots", "app_launch", "realtime_voice", "gateway_chat"))
            )
        val accepted = webSocket.send(register.toString())
        Log.i(TAG, "register sent=$accepted deviceId=${config.deviceId}")
        val statusText = "Authenticating with ${activeHostUrl()}"
        onStatus(statusText, "info")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.CONNECTING, statusText))
        scheduleRegisterTimeout(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runOnMain { handleMessage(webSocket, text) }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        if (webSocket != socket) return
        val message = BridgeIncomingMessageParser.parse(text).getOrElse { error ->
            val statusText = "Ignored malformed bridge message: ${error.message ?: error::class.java.simpleName}"
            Log.w(TAG, statusText)
            onStatus(statusText, "error")
            reportBridgeChatError("Bridge sent malformed JSON; ignored the message.")
            return
        }
        if (message.optString("type").startsWith("realtime.")) {
            Log.i(TAG, "received ${message.optString("type")}")
        }
        if (!registered) {
            when (val frame = PreRegistrationMessageGate.evaluate(message, config.deviceId)) {
                is PreRegistrationFrame.Registered -> {
                    registered = true
                    connected = true
                    endpointIndex = endpointUrls().indexOf(activeHostUrl()).coerceAtLeast(0)
                    cancelRegisterTimeout()
                    sendChatOpen()
                    val connectedText = "Connected and registered as ${config.deviceId}"
                    onStatus(connectedText, "info")
                    onConnectionState(BridgeConnectionState(BridgeConnectionPhase.CONNECTED, connectedText))
                    return
                }
                is PreRegistrationFrame.Status -> {
                    onStatus(frame.text, frame.status)
                    return
                }
                is PreRegistrationFrame.Rejected -> {
                    Log.w(TAG, "Rejected ${frame.messageType} before registration")
                    webSocket.close(1002, "message before registration")
                    return
                }
            }
        }
        when (message.optString("type")) {
            "command" -> handleCommand(webSocket, message)
            "agent_status" -> onStatus(message.optString("text"), message.optString("status", "info"))
            "chat.state",
            "chat.history",
            "chat.message",
            "chat.delta",
            "chat.reasoning_delta",
            "chat.reasoning_clear",
            "chat.final",
            "chat.error",
            "chat.reply_available",
            "chat.tool_event",
            "chat.commands",
            "chat.tools",
            "chat.sessions",
            "chat.usage" -> onChatMessage(message)
            "chat.models" -> onChatMessage(message.put("source", "host"))
            "realtime.sdp" -> onRealtimeSdp(message)
            "realtime.transcript_delta" -> onRealtimeTranscriptDelta(message)
            "realtime.item_added" -> onRealtimeItemAdded(message)
            "realtime.speech_started" -> onRealtimeSpeechStarted(message)
            "realtime.error" -> onRealtimeError(message)
            "realtime.closed" -> onRealtimeClosed(message)
            "realtime.tool_result" -> onRealtimeToolResult(message)
            "realtime.task_status" -> onRealtimeTaskStatus(message)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        runOnMain { handleFailure(webSocket, t) }
    }

    private fun handleFailure(webSocket: WebSocket, t: Throwable) {
        if (webSocket != socket) return
        socket = null
        connected = false
        registered = false
        cancelRegisterTimeout()
        val statusText = "WebSocket error: ${t.message}"
        onStatus(statusText, "error")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.ERROR, statusText))
        reportBridgeChatError("Bridge connection failed: ${t.message ?: "unknown error"}")
        advanceEndpointCandidate()
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        runOnMain { handleClosed(webSocket, code, reason) }
    }

    private fun handleClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket != socket) return
        socket = null
        connected = false
        registered = false
        cancelRegisterTimeout()
        val (statusText, longBackoff) = when (code) {
            4001 -> {
                "Bridge rejected token (4001 $reason). Update PHONE_AGENT_TOKEN on the PC or re-pair from app Settings." to true
            }
            4002 -> {
                "Bridge required register first (4002 $reason); reconnecting..." to false
            }
            4003 -> {
                "Bridge did not acknowledge registration; reconnecting..." to false
            }
            else -> "Disconnected: $reason (code $code)" to false
        }
        onStatus(statusText, "error")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.ERROR, statusText))
        reportBridgeChatError(statusText)
        if (!longBackoff) {
            advanceEndpointCandidate()
        }
        scheduleReconnect(longBackoff)
    }

    private fun sendJson(message: JSONObject, reportChatError: Boolean = false): Boolean {
        val type = message.optString("type", "message")
        val sent = connected && registered && socket?.send(message.toString()) == true
        Log.i(TAG, "send $type sent=$sent connected=$connected registered=$registered")
        if (!sent) {
            val error = "Bridge is not registered. Check the PC bridge at ${activeHostUrl()}; reconnecting..."
            runOnMain {
                onStatus(error, "error")
                onConnectionState(BridgeConnectionState(BridgeConnectionPhase.ERROR, error))
                if (reportChatError) {
                    reportBridgeChatError(error)
                }
            }
        }
        return sent
    }

    private fun reportBridgeChatError(message: String) {
        runOnMain {
            onChatMessage(JSONObject()
                .put("type", "chat.error")
                .put("deviceId", config.deviceId)
                .put("message", message))
        }
    }

    private fun scheduleReconnect(longBackoff: Boolean = false) {
        if (manuallyClosed || reconnectRunnable != null) {
            return
        }
        val delayMs = if (longBackoff) {
            TOKEN_REJECTED_BACKOFF_MS
        } else {
            (1_000L * (reconnectAttempts + 1)).coerceAtMost(10_000L)
        }
        reconnectAttempts += 1
        val task = Runnable {
            reconnectRunnable = null
            if (!manuallyClosed && !connected) {
                connect()
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleRegisterTimeout(webSocket: WebSocket) {
        cancelRegisterTimeout()
        val task = Runnable {
            registerTimeoutRunnable = null
            if (!registered && webSocket == socket) {
                Log.w(TAG, "register ack timed out after ${REGISTER_TIMEOUT_MS}ms")
                handleHandshakeFailure(webSocket, "Bridge did not acknowledge registration within ${REGISTER_TIMEOUT_MS / 1000}s. Confirm PHONE_AGENT_TOKEN matches the app's saved token and the bridge is running.")
            }
        }
        registerTimeoutRunnable = task
        mainHandler.postDelayed(task, REGISTER_TIMEOUT_MS)
    }

    private fun handleHandshakeFailure(webSocket: WebSocket, statusText: String) {
        socket = null
        connected = false
        registered = false
        cancelRegisterTimeout()
        webSocket.cancel()
        onStatus(statusText, "error")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.ERROR, statusText))
        reportBridgeChatError(statusText)
        advanceEndpointCandidate()
        scheduleReconnect()
    }

    private fun endpointUrls(): List<String> {
        return (listOf(config.hostUrl) + config.hostUrlCandidates)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(config.hostUrl) }
    }

    private fun activeHostUrl(): String {
        val endpoints = endpointUrls()
        return endpoints[endpointIndex.coerceIn(0, endpoints.lastIndex)]
    }

    private fun advanceEndpointCandidate() {
        val endpoints = endpointUrls()
        if (endpoints.size <= 1) {
            return
        }
        endpointIndex = (endpointIndex + 1) % endpoints.size
        val statusText = "Trying alternate bridge endpoint ${activeHostUrl()}"
        onStatus(statusText, "info")
        onConnectionState(BridgeConnectionState(BridgeConnectionPhase.CONNECTING, statusText))
    }

    private fun cancelRegisterTimeout() {
        registerTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        registerTimeoutRunnable = null
    }

    private fun handleCommand(webSocket: WebSocket, message: JSONObject) {
        val id = message.optString("id").takeIf { it.isNotBlank() }
        val command = message.optString("command").takeIf { it.isNotBlank() }
        if (id == null || command == null) {
            reportBridgeChatError("Bridge sent a malformed command message; ignored it.")
            return
        }
        val args = message.optJSONObject("args") ?: JSONObject()
        commandExecutor.execute(command, args) { result ->
            val response = JSONObject()
                .put("id", id)
                .put("type", "result")
                .put("ok", result.ok)
                .put("observation", result.observation)
                .put("error", result.error)
            result.screenshotBase64?.let { response.put("screenshotBase64", it) }
            result.screenshot?.let { response.put("screenshot", it) }
            webSocket.send(response.toString())
        }
    }

    private fun dispatchRealtimeError(message: String) {
        runOnMain {
            onRealtimeError(JSONObject().put("type", "realtime.error").put("message", message))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "PhoneWebSocketClient"
        private const val REGISTER_TIMEOUT_MS = 5_000L
        private const val TOKEN_REJECTED_BACKOFF_MS = 30_000L
    }
}
