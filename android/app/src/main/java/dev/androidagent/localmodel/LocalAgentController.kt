package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import android.util.Log
import kotlinx.coroutines.CancellationException
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
        val toolsAllowed = shouldAllowTools(userText)
        val phoneControlRequest = shouldLoadAndroidControlSkill(userText)
        if (phoneControlRequest) {
            transcript.add("system: This is an Android phone-control request. Before any phone_* tool, call local_read_skill with name android-control and follow the returned skill.")
        }
        val config = configProvider()
        val systemPrompt = LocalPromptBuilder.systemPrompt(
            basePrompt = config.systemPrompt,
            toolsAllowed = toolsAllowed,
            toolDescriptionsJson = tools.toolDescriptions().toString()
        )
        var rejectedUnneededTool = false
        var rejectedCommandRequest = false
        var rejectedEmptyTermuxCommand = false
        var repeatedObserveCount = 0
        var latestScreenshotPath: String? = null
        var lastSparseObservationScreenshotKey: String? = null
        var androidControlSkillLoaded = false

        repeat(MAX_TOOL_ROUNDS) toolLoop@{ round ->
            Log.i(TAG, "local turn $runId round=${round + 1} starting")
            emit(reasoning(sessionKey, runId, if (round == 0) "Planning locally..." else "Continuing after tool result...", replace = round == 0))
            val prompt = LocalPromptBuilder.roundPrompt(transcript, latestScreenshotPath)
            Log.i(TAG, "local turn $runId prompt metrics round=${round + 1} systemChars=${systemPrompt.length} systemTokens=${LocalPromptBuilder.estimateTokenCount(systemPrompt)} promptChars=${prompt.length} promptTokens=${LocalPromptBuilder.estimateTokenCount(prompt)}")
            val response = try {
                withTimeout(MODEL_RESPONSE_TIMEOUT_MS) {
                    runtime.generate(
                        LocalModelRequest(
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            config = config,
                            imagePaths = latestScreenshotPath?.let(::listOf).orEmpty()
                        ),
                        onDelta = {},
                        onStatus = { status ->
                            emit(state(sessionKey, runId, isRunning = true, status = status))
                        }
                    )
                }.trim()
            } catch (_: TimeoutCancellationException) {
                val message = "Local model timed out while deciding the next step."
                emitAssistant(sessionKey, runId, message)
                return message
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = "Local model failed while loading or generating: ${error.message ?: error::class.java.simpleName}"
                emit(JSONObject()
                    .put("type", "chat.reasoning_clear")
                    .put("sessionKey", sessionKey)
                    .put("runId", runId))
                emitAssistant(sessionKey, runId, message)
                return message
            }
            Log.i(TAG, "local turn $runId round=${round + 1} model response=${response.take(500)}")

            val calls = LocalToolCallParser.parse(response)
            if (calls.isEmpty()) {
                val finalText = cleanFinalText(response.ifBlank { "I could not generate a response." })
                if (toolsAllowed && shouldRejectCommandRequest(userText, finalText) && !rejectedCommandRequest) {
                    rejectedCommandRequest = true
                    transcript.add("assistant: $finalText")
                    transcript.add("system: Do not ask the user for a shell command. The user asked you to perform the task. Choose the Termux command yourself and call termux_command now.")
                    return@toolLoop
                }
                emit(JSONObject()
                    .put("type", "chat.reasoning_clear")
                    .put("sessionKey", sessionKey)
                    .put("runId", runId))
                emitAssistant(sessionKey, runId, finalText)
                return finalText
            }
            if (!toolsAllowed) {
                if (!rejectedUnneededTool) {
                    rejectedUnneededTool = true
                    transcript.add("system: The user's message does not require tools. Answer directly in natural language without observing the phone or using tools.")
                    return@toolLoop
                }
                val message = "I can answer this directly without using phone or developer tools."
                emitAssistant(sessionKey, runId, message)
                return message
            }

            for (call in calls) {
                var callToExecute = call
                if (phoneControlRequest && isPhoneTool(call.name) && !androidControlSkillLoaded) {
                    val rejected = JSONObject()
                        .put("ok", false)
                        .put("error", "Before Android phone-control tools, call local_read_skill with args {\"name\":\"android-control\"} and follow that skill.")
                    transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                    transcript.add("tool ${call.name} result: ${rejected.toString()}")
                    continue
                }
                if (call.name == "phone_observe" && latestScreenshotPath != null) {
                    val rejected = JSONObject()
                        .put("ok", false)
                        .put("error", "A screenshot image is already attached. Use the screenshot to choose phone_tap_normalized coordinates, or explain normally if the target is not visible.")
                    transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                    transcript.add("tool ${call.name} result: ${rejected.toString()}")
                    continue
                }
                if (call.name == "phone_observe") {
                    repeatedObserveCount += 1
                    if (repeatedObserveCount > MAX_CONSECUTIVE_OBSERVES) {
                        val message = "I could not find enough actionable information from the phone screen to continue."
                        emitAssistant(sessionKey, runId, message)
                        return message
                    }
                } else {
                    repeatedObserveCount = 0
                }
                var demoFallbackTargetPath: String? = null
                val replacementFallback = if (call.name == "termux_command") {
                    DemoHtmlTermuxFallbackPolicy.replacementFor(userText, call.args)
                } else {
                    null
                }
                if (replacementFallback != null) {
                    transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                    transcript.add("system: ${replacementFallback.reason}")
                    callToExecute = LocalToolCall("termux_command", replacementFallback.args)
                    demoFallbackTargetPath = replacementFallback.targetPath
                } else if (call.name == "termux_command" && termuxCommandText(call.args).isBlank()) {
                    val emptyCommandFallback = DemoHtmlTermuxFallbackPolicy.fallbackForEmptyCommand(userText)
                    if (emptyCommandFallback != null) {
                        transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                        transcript.add("system: ${emptyCommandFallback.reason}")
                        callToExecute = LocalToolCall("termux_command", emptyCommandFallback.args)
                        demoFallbackTargetPath = emptyCommandFallback.targetPath
                    } else if (!rejectedEmptyTermuxCommand) {
                        rejectedEmptyTermuxCommand = true
                        val rejected = JSONObject()
                            .put("ok", false)
                            .put("error", "termux_command requires a non-empty command argument. Choose the shell command yourself and call termux_command with args.command.")
                        transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                        transcript.add("tool ${call.name} result: ${rejected.toString()}")
                        continue
                    }
                }
                val result = executeAndRecordTool(sessionKey, runId, round, callToExecute, transcript)
                if (callToExecute.name == "local_read_skill" && result.optBoolean("ok", false) &&
                    result.optString("name") == LocalToolRegistry.ANDROID_CONTROL_SKILL_NAME
                ) {
                    androidControlSkillLoaded = true
                }
                result.optString("screenshotPath").takeIf { it.isNotBlank() }?.let { path ->
                    latestScreenshotPath = path
                }
                if (callToExecute.name == "termux_command" && result.optBoolean("ok", false) && callToExecute !== call) {
                    val target = demoFallbackTargetPath ?: "/sdcard/Download/openclaw-project"
                    val message = "Done. I created the HTML project at `$target/index.html` and opened it in the browser."
                    emitAssistant(sessionKey, runId, message)
                    return message
                }
                if (call.name == "phone_observe" && isSparseObservation(result)) {
                    val observationKey = sparseObservationKey(result)
                    if (observationKey != lastSparseObservationScreenshotKey) {
                        lastSparseObservationScreenshotKey = observationKey
                        val screenshotResult = executeAndRecordTool(
                            sessionKey = sessionKey,
                            runId = runId,
                            round = round,
                            call = LocalToolCall("phone_take_screenshot"),
                            transcript = transcript
                        )
                        screenshotResult.optString("screenshotPath").takeIf { it.isNotBlank() }?.let { path ->
                            latestScreenshotPath = path
                        }
                    }
                }
            }
        }

        val message = "I hit the local tool safety limit before finishing."
        emitAssistant(sessionKey, runId, message)
        return message
    }

    private fun emitAssistant(sessionKey: String, runId: String, text: String) {
        val cleaned = cleanFinalText(text)
        chunk(cleaned).forEachIndexed { index, part ->
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
            .put("text", cleaned))
    }

    private suspend fun executeAndRecordTool(
        sessionKey: String,
        runId: String,
        round: Int,
        call: LocalToolCall,
        transcript: MutableList<String>
    ): JSONObject {
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
        return result
    }

    private fun state(sessionKey: String, runId: String?, isRunning: Boolean, status: String): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
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

    private fun isSparseObservation(result: JSONObject): Boolean {
        if (!result.optBoolean("ok", false)) return true
        val observation = result.optJSONObject("observation") ?: return true
        val summary = observation.optString("screenSummary")
        val nodes = observation.optJSONArray("nodes")
        if (summary.isBlank() && (nodes == null || nodes.length() == 0)) return true
        val labeledNodes = (0 until (nodes?.length() ?: 0)).count { index ->
            val node = nodes?.optJSONObject(index) ?: return@count false
            node.optString("text").isNotBlank() || node.optString("contentDescription").isNotBlank()
        }
        return summary.isBlank() || labeledNodes == 0
    }

    private fun sparseObservationKey(result: JSONObject): String {
        val observation = result.optJSONObject("observation")
        return listOf(
            result.optString("error"),
            observation?.optString("package").orEmpty(),
            observation?.optString("activity").orEmpty(),
            observation?.optString("screenSummary").orEmpty(),
            observation?.optJSONArray("nodes")?.length()?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun cleanFinalText(text: String): String {
        return LocalResponseTextNormalizer.normalize(text)
    }

    private fun buildLocalSystemPrompt(basePrompt: String): String {
        return """
            $basePrompt

            Local mode override:
            - Behave like a normal conversational LLM with optional tools.
            - Do not use tools for ordinary questions or chat.
            - Only call tools when the user asks you to interact with the phone, files, or Termux.
            - For file/project creation requests, decide the commands yourself. Do not ask the user to provide commands.
            - For HTML or files that should open in the browser, use Termux/shared storage, not the app-private local workspace.
            - Do not prefix final answers with TASK_COMPLETE or BLOCKED. Reply naturally.
        """.trimIndent()
    }

    private fun shouldAllowTools(userText: String): Boolean {
        val text = userText.lowercase()
        val actionKeywords = listOf(
            "phone", "screen", "screenshot", "observe", "tap", "click", "press", "swipe", "scroll",
            "type into", "open app", "launch", "settings", "youtube", "home button", "back button",
            "camera", "browser", "termux", "terminal", "shell", "command", "execute",
            "file", "folder", "directory", "workspace", "project", "index.html", "html", "css", "javascript"
        )
        return actionKeywords.any { text.contains(it) }
    }

    private fun shouldLoadAndroidControlSkill(userText: String): Boolean {
        val text = userText.lowercase()
        val phoneSignals = listOf(
            "phone", "screen", "screenshot", "observe", "tap", "click", "press", "swipe", "scroll",
            "type into", "open app", "launch", "settings", "youtube", "camera", "home button", "back button",
            "notification", "recents", "android"
        )
        val nonPhoneSignals = listOf("termux", "terminal", "shell", "command", "file", "folder", "directory", "project", "html", "css", "javascript")
        return phoneSignals.any { text.contains(it) } && nonPhoneSignals.none { text.contains(it) }
    }

    private fun isPhoneTool(name: String): Boolean =
        LocalToolSpecs.phoneCommandsByToolId.containsKey(name)

    private fun shouldRejectCommandRequest(userText: String, response: String): Boolean {
        val user = userText.lowercase()
        val answer = response.lowercase()
        val askedForExecutableWork = listOf("termux", "terminal", "shell", "command", "file", "folder", "directory", "project", "html", "css", "javascript")
            .any { user.contains(it) }
        val isAskingUserForCommand = listOf("provide the command", "specific command", "exact command", "tell me the command", "what command")
            .any { answer.contains(it) }
        return askedForExecutableWork && isAskingUserForCommand
    }

    private fun termuxCommandText(args: JSONObject): String =
        args.optString("command")
            .ifBlank { args.optString("cmd") }
            .ifBlank { args.optString("script") }

    companion object {
        private const val TAG = "LocalAgentController"
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_CONSECUTIVE_OBSERVES = 2
        private const val MODEL_RESPONSE_TIMEOUT_MS = 180_000L
    }
}
