package dev.androidagent.voice

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentLocationProvider
import dev.androidagent.ChatClientRoute
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.net.PhoneWebSocketClient
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

internal class RealtimeVoiceCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val commandExecutor: () -> AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig,
    private val selectedModel: (AgentConfig) -> String,
    private val routeForModel: (String, AgentConfig) -> ChatClientRoute,
    private val modelForRoute: (String, ChatClientRoute, AgentConfig) -> String,
    private val selectedReasoningEffort: () -> String?,
    private val webSocketClient: () -> PhoneWebSocketClient?,
    private val onStatus: (String, String) -> Unit,
    private val onChatMessage: (JSONObject) -> Unit,
    private val onRealtimeToolResult: (JSONObject) -> Unit,
    private val onRealtimeTaskStatus: (JSONObject) -> Unit
) {
    private var localDelegate: LocalRealtimeVoiceDelegate? = null

    fun sendStart(sdp: String, config: AgentConfig) {
        webSocketClient()?.sendRealtimeStart(
            sdp,
            realtimeRequestConfig(config),
            AgentLocationProvider.currentBestEffortLocation(context)
        )
    }

    fun sendStop(reason: String) {
        webSocketClient()?.sendRealtimeStop(reason)
    }

    fun handleToolCall(call: RealtimeToolCall) {
        val config = configProvider()
        val model = selectedModel(config)
        val intent = RealtimeToolRouting.intentFor(call.name, call.arguments)
        when (RealtimeToolRouting.routeFor(model, intent)) {
            RealtimeToolExecutionRoute.Local -> localDelegate().handleToolCall(call)
            RealtimeToolExecutionRoute.Bridge -> webSocketClient()?.sendRealtimeToolCall(call, realtimeRequestConfig(config))
        }
    }

    fun close() {
        localDelegate?.close()
        localDelegate = null
    }

    private fun localDelegate(): LocalRealtimeVoiceDelegate {
        localDelegate?.let { return it }
        return LocalRealtimeVoiceDelegate(
            context = context,
            scope = scope,
            commandExecutor = commandExecutor(),
            configProvider = configProvider,
            onStatus = onStatus,
            onChatMessage = onChatMessage,
            onRealtimeToolResult = onRealtimeToolResult,
            onRealtimeTaskStatus = onRealtimeTaskStatus
        ).also { localDelegate = it }
    }

    private fun realtimeRequestConfig(config: AgentConfig = configProvider()): AgentConfig {
        val model = selectedModel(config)
        val route = routeForModel(model, config)
        return config.copy(
            model = modelForRoute(model, route, config),
            reasoningEffort = selectedReasoningEffort() ?: config.reasoningEffort
        )
    }
}
