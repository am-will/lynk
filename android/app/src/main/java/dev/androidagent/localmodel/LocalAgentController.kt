package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

class LocalAgentController(
    private val runtime: LocalModelRuntime,
    private val tools: LocalToolRegistry,
    private val configProvider: () -> AgentConfig,
    private val emit: (JSONObject) -> Unit
) {
    suspend fun run(
        sessionKey: String,
        runId: String,
        userText: String,
        history: List<LocalChatMessage>
    ): String {
        emit(state(sessionKey, runId, isRunning = true, status = "Local model is working"))
        val transcript = history.takeLast(16).map { "${it.role}: ${it.text}" }.toMutableList()
        transcript.add("user: $userText")

        repeat(MAX_TOOL_ROUNDS) { round ->
            Log.i(TAG, "local turn $runId round=${round + 1} starting")
            emit(reasoning(sessionKey, runId, if (round == 0) "Planning locally..." else "Continuing after tool result...", replace = round == 0))
            val response = try {
                withTimeout(MODEL_RESPONSE_TIMEOUT_MS) {
                    runtime.generate(
                        LocalModelRequest(
                            prompt = buildPrompt(transcript),
                            systemPrompt = configProvider().systemPrompt,
                            config = configProvider()
                        ),
                        onDelta = {}
                    )
                }.trim()
            } catch (_: TimeoutCancellationException) {
                val blocked = "BLOCKED: Local model timed out while deciding the next phone-control step."
                emitAssistant(sessionKey, runId, blocked)
                return blocked
            }
            Log.i(TAG, "local turn $runId round=${round + 1} model response=${response.take(500)}")

            val calls = LocalToolCallParser.parse(response)
            if (calls.isEmpty()) {
                val finalText = response.ifBlank { "BLOCKED: Local model returned an empty response." }
                emit(JSONObject()
                    .put("type", "chat.reasoning_clear")
                    .put("sessionKey", sessionKey)
                    .put("runId", runId))
                emitAssistant(sessionKey, runId, finalText)
                return finalText
            }

            for (call in calls) {
                Log.i(TAG, "local turn $runId executing tool=${call.name} args=${call.args}")
                val eventId = "local_tool_${UUID.randomUUID()}"
                emit(toolEvent(sessionKey, runId, eventId, call, "running", "Running ${call.name}", null, null))
                val result = runCatching { tools.execute(call) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.toString()) }
                Log.i(TAG, "local turn $runId tool=${call.name} ok=${result.optBoolean("ok", false)} error=${result.optString("error")}")
                emit(toolEvent(
                    sessionKey = sessionKey,
                    runId = runId,
                    eventId = eventId,
                    call = call,
                    status = if (result.optBoolean("ok", false)) "completed" else "failed",
                    title = call.name,
                    output = result,
                    error = result.optString("error").takeIf { it.isNotBlank() }
                ))
                emit(state(
                    sessionKey = sessionKey,
                    runId = runId,
                    isRunning = true,
                    status = if (result.optBoolean("ok", false)) {
                        "Local tool ${call.name} completed; continuing"
                    } else {
                        "Local tool ${call.name} failed; deciding next step"
                    }
                ))
                transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                transcript.add("tool ${call.name} result: ${result.toString().take(12_000)}")
            }
        }

        val blocked = "BLOCKED: Local tool loop reached its safety limit before completing the task."
        emitAssistant(sessionKey, runId, blocked)
        return blocked
    }

    private fun buildPrompt(transcript: List<String>): String {
        return """
            You are running locally on this Android phone as OpenClaw Agent.

            You must keep working until the user's full request is complete, blocked, or needs manual confirmation. Do not stop after the first successful tool if the user asked for multiple steps. After every tool result, compare the observed phone state against the original user request and decide the next action.

            Use tools when you need phone state, Android app control, local workspace access, or confirmation. If you need a tool, respond with only JSON:
            {"tool":"phone_observe","args":{}}
            or
            {"toolCalls":[{"name":"phone_open_app","args":{"appName":"Settings"}}]}

            If the requested final state is visible in a tool result, answer with TASK_COMPLETE and a short confirmation. If you cannot continue, answer with BLOCKED and the exact reason. Do not emit an empty response.

            Available tools:
            ${tools.toolDescriptions()}

            If no tool is needed, answer normally and concisely.

            Conversation:
            ${transcript.joinToString("\n")}
        """.trimIndent()
    }

    private fun emitAssistant(sessionKey: String, runId: String, text: String) {
        chunk(text).forEachIndexed { index, part ->
            emit(JSONObject()
                .put("type", "chat.delta")
                .put("sessionKey", sessionKey)
                .put("runId", runId)
                .put("delta", part)
                .put("replace", index == 0))
        }
        emit(JSONObject()
            .put("type", "chat.final")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("text", text))
    }

    private fun state(sessionKey: String, runId: String?, isRunning: Boolean, status: String): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("model", "local-litertlm")
            .put("reasoningEffort", configProvider().reasoningEffort)

    private fun reasoning(sessionKey: String, runId: String, text: String, replace: Boolean): JSONObject =
        JSONObject()
            .put("type", "chat.reasoning_delta")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("delta", text)
            .put("replace", replace)

    private fun toolEvent(
        sessionKey: String,
        runId: String,
        eventId: String,
        call: LocalToolCall,
        status: String,
        title: String,
        output: JSONObject?,
        error: String?
    ): JSONObject =
        JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("eventId", eventId)
            .put("toolName", call.name)
            .put("title", title)
            .put("status", status)
            .put("args", call.args)
            .put("output", output)
            .put("error", error)

    private fun chunk(text: String): List<String> {
        if (text.length <= 80) return listOf(text)
        return text.chunked(80)
    }

    companion object {
        private const val TAG = "LocalAgentController"
        private const val MAX_TOOL_ROUNDS = 8
        private const val MODEL_RESPONSE_TIMEOUT_MS = 180_000L
    }
}
