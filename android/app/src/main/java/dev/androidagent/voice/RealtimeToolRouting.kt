package dev.androidagent.voice

import dev.androidagent.AgentModelOptions
import org.json.JSONObject

enum class RealtimeToolExecutionRoute {
    Bridge,
    Local
}

enum class RealtimeTaskIntentKind {
    General,
    Phone
}

sealed interface RealtimeToolIntent {
    data class StartTask(
        val kind: RealtimeTaskIntentKind,
        val instruction: String
    ) : RealtimeToolIntent

    data class SteerTask(
        val kind: RealtimeTaskIntentKind,
        val guidance: String
    ) : RealtimeToolIntent

    data class StopTask(
        val kind: RealtimeTaskIntentKind,
        val reason: String
    ) : RealtimeToolIntent

    data object BridgeOnly : RealtimeToolIntent

    data class Unsupported(
        val toolName: String
    ) : RealtimeToolIntent
}

object RealtimeToolRouting {
    fun routeFor(selectedModel: String?, toolName: String): RealtimeToolExecutionRoute {
        return routeFor(selectedModel, intentFor(toolName, JSONObject()))
    }

    fun routeFor(selectedModel: String?, intent: RealtimeToolIntent): RealtimeToolExecutionRoute {
        return if (selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID && intent.isLocalCapable()) {
            RealtimeToolExecutionRoute.Local
        } else {
            RealtimeToolExecutionRoute.Bridge
        }
    }

    fun intentFor(toolName: String, arguments: JSONObject): RealtimeToolIntent =
        when (toolName) {
            "delegate_agent_task", "delegate_openclaw_task" ->
                RealtimeToolIntent.StartTask(
                    kind = RealtimeTaskIntentKind.General,
                    instruction = instruction(arguments)
                )
            "run_phone_task" ->
                RealtimeToolIntent.StartTask(
                    kind = RealtimeTaskIntentKind.Phone,
                    instruction = instruction(arguments)
                )
            "steer_agent_task", "steer_openclaw_task" ->
                RealtimeToolIntent.SteerTask(
                    kind = RealtimeTaskIntentKind.General,
                    guidance = guidance(arguments)
                )
            "steer_phone_task" ->
                RealtimeToolIntent.SteerTask(
                    kind = RealtimeTaskIntentKind.Phone,
                    guidance = guidance(arguments)
                )
            "stop_agent_task", "stop_openclaw_task" ->
                RealtimeToolIntent.StopTask(
                    kind = RealtimeTaskIntentKind.General,
                    reason = reason(arguments)
                )
            "stop_phone_task" ->
                RealtimeToolIntent.StopTask(
                    kind = RealtimeTaskIntentKind.Phone,
                    reason = reason(arguments)
                )
            "hang_up_realtime", "web_search" -> RealtimeToolIntent.BridgeOnly
            else -> RealtimeToolIntent.Unsupported(toolName)
        }

    private fun RealtimeToolIntent.isLocalCapable(): Boolean =
        when (this) {
            is RealtimeToolIntent.StartTask,
            is RealtimeToolIntent.SteerTask,
            is RealtimeToolIntent.StopTask -> true
            RealtimeToolIntent.BridgeOnly,
            is RealtimeToolIntent.Unsupported -> false
        }

    private fun instruction(arguments: JSONObject): String =
        arguments.optString("instruction")
            .ifBlank { arguments.optString("task") }
            .trim()

    private fun guidance(arguments: JSONObject): String =
        arguments.optString("guidance")
            .trim()

    private fun reason(arguments: JSONObject): String =
        arguments.optString("reason")
            .ifBlank { "Stopped by realtime voice" }
            .trim()
}
