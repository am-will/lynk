package dev.androidagent.voice

import dev.androidagent.AgentModelOptions
import org.json.JSONObject

enum class RealtimeToolExecutionRoute {
    Bridge,
    Local
}

object RealtimeToolRouting {
    private val localTaskTools = setOf(
        "delegate_agent_task",
        "delegate_openclaw_task",
        "run_phone_task",
        "steer_agent_task",
        "steer_openclaw_task",
        "steer_phone_task",
        "stop_agent_task",
        "stop_openclaw_task",
        "stop_phone_task"
    )
    private val stopTools = setOf("stop_agent_task", "stop_openclaw_task", "stop_phone_task")

    fun routeFor(selectedModel: String?, toolName: String): RealtimeToolExecutionRoute {
        return if (selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID && toolName in localTaskTools) {
            RealtimeToolExecutionRoute.Local
        } else {
            RealtimeToolExecutionRoute.Bridge
        }
    }

    fun isStopTool(toolName: String): Boolean = toolName in stopTools

    fun instruction(arguments: JSONObject): String =
        arguments.optString("instruction")
            .ifBlank { arguments.optString("task") }
            .ifBlank { arguments.optString("guidance") }
            .trim()
}
