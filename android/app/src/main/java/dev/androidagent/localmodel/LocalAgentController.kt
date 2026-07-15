package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.androidagent.agentchat.LocalTurnRunner

internal fun selectNewestHistory(
    history: List<LocalChatMessage>,
    runtimeProfile: LocalModelRuntimeProfile,
    systemPrompt: String,
    currentUserText: String
): List<LocalChatMessage> {
    val contextTokens = runtimeProfile.effectiveContextTokens
    val outputReserve = (contextTokens / 4).coerceIn(512, 8_192)
    val toolRoundReserve = (contextTokens / 4).coerceIn(512, 8_192)
    val fixedTokens = LocalPromptBuilder.estimateTokenCount(systemPrompt) +
        LocalPromptBuilder.estimateTokenCount("user: $currentUserText") +
        LocalPromptBuilder.estimateTokenCount(LocalPromptBuilder.roundPrompt(emptyList(), null))
    var remainingTokens = (contextTokens - outputReserve - toolRoundReserve - fixedTokens).coerceAtLeast(0)
    val selectedNewestFirst = mutableListOf<LocalChatMessage>()
    for (message in history.asReversed()) {
        val messageTokens = LocalPromptBuilder.estimateTokenCount("${message.role}: ${message.text}")
        if (messageTokens > remainingTokens) break
        selectedNewestFirst += message
        remainingTokens -= messageTokens
    }
    return selectedNewestFirst.asReversed()
}

internal fun imagePathsForRound(
    runtimeProfile: LocalModelRuntimeProfile,
    initialImagePaths: List<String>,
    latestScreenshotPath: String?
): List<String> {
    if (!runtimeProfile.supportsImageInput) return emptyList()
    return latestScreenshotPath?.let(::listOf) ?: initialImagePaths.take(1)
}

class LocalAgentController(
    private val runtime: LocalModelRuntime,
    private val tools: LocalToolRegistry,
    private val configProvider: () -> AgentConfig,
    private val emit: (JSONObject) -> Unit
) : LocalTurnRunner {
    override suspend fun run(
        sessionKey: String,
        runId: String,
        userText: String,
        history: List<LocalChatMessage>,
        imagePaths: List<String>
    ): String {
        val toolAccess = LocalToolPolicy.accessFor(userText)
        val phoneControlRequest = toolAccess.phoneControl
        val toolsAllowed = toolAccess.allowsAny
        val multiStepPhoneRequest = phoneControlRequest && LocalPhoneControlTurnPolicy.isMultiStepRequest(userText)
        val config = configProvider()
        val preflightProfile = runtime.profile(config)
        if (imagePaths.isNotEmpty() && !preflightProfile.supportsImageInput) {
            val message = checkNotNull(preflightProfile.imageInputUnsupportedMessage)
            emitAssistant(sessionKey, runId, message)
            return message
        }
        emit(state(
            sessionKey = sessionKey,
            runId = runId,
            isRunning = true,
            status = "Local model is working",
            taskKind = if (phoneControlRequest) "phone" else null
        ))
        LocalPhoneControlTurnPolicy.directOpenAppName(userText)?.let { appName ->
            val call = LocalToolCall("phone_open_app", JSONObject().put("appName", appName))
            val result = executeAndRecordTool(
                sessionKey,
                runId,
                0,
                call,
                mutableListOf("user: $userText"),
                preflightProfile
            )
            val message = if (result.optBoolean("ok", false)) {
                "Opened $appName."
            } else {
                "BLOCKED: ${result.optString("error").ifBlank { "Could not open $appName." }}"
            }
            emit(JSONObject()
                .put("type", "chat.reasoning_clear")
                .put("sessionKey", sessionKey)
                .put("runId", runId))
            emitAssistant(sessionKey, runId, message)
            return message
        }
        val runtimeProfile = try {
            withTimeout(MODEL_RESPONSE_TIMEOUT_MS) {
                runtime.resolveProfile(config) { status ->
                    emit(state(sessionKey, runId, isRunning = true, status = status))
                }
            }
        } catch (_: TimeoutCancellationException) {
            val message = "Local model timed out while loading."
            emitAssistant(sessionKey, runId, message)
            return message
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = "Local model failed while loading: ${error.message ?: error::class.java.simpleName}"
            emitAssistant(sessionKey, runId, message)
            return message
        }
        val systemPrompt = LocalPromptBuilder.systemPrompt(
            basePrompt = config.systemPrompt,
            toolsAllowed = toolsAllowed,
            toolDescriptionsJson = tools.toolDescriptions(runtimeProfile, toolAccess).toString()
        )
        val transcript = selectNewestHistory(
            history = history,
            runtimeProfile = runtimeProfile,
            systemPrompt = systemPrompt,
            currentUserText = userText
        ).map { "${it.role}: ${it.text}" }.toMutableList()
        transcript.add("user: $userText")
        if (phoneControlRequest) {
            transcript.add("system: This is an Android phone-control request. Use the available phone tools directly, then verify the requested result before answering.")
        }
        var rejectedUnneededTool = false
        var repeatedObserveCount = 0
        var latestScreenshotPath: String? = null
        var phoneToolExecuted = false
        var phoneActionCount = 0
        var noToolPhoneNudges = 0

        repeat(MAX_TOOL_ROUNDS) toolLoop@{ round ->
            Log.i(TAG, "local turn $runId round=${round + 1} starting")
            emit(reasoning(sessionKey, runId, if (round == 0) "Planning locally..." else "Continuing after tool result...", replace = round == 0))
            val prompt = LocalPromptBuilder.roundPrompt(transcript, latestScreenshotPath)
            Log.i(TAG, "local turn $runId prompt metrics round=${round + 1} systemChars=${systemPrompt.length} systemTokens=${LocalPromptBuilder.estimateTokenCount(systemPrompt)} promptChars=${prompt.length} promptTokens=${LocalPromptBuilder.estimateTokenCount(prompt)}")
            val response = try {
                val streamed = StringBuilder()
                withTimeout(MODEL_RESPONSE_TIMEOUT_MS) {
                    runtime.generate(
                        LocalModelRequest(
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            config = config,
                            imagePaths = imagePathsForRound(runtimeProfile, imagePaths, latestScreenshotPath)
                        ),
                        onDelta = { delta ->
                            streamed.append(delta)
                            if (!toolsAllowed) {
                                val visible = LocalResponseTextNormalizer.visibleStreamingText(streamed.toString())
                                if (visible.isNotEmpty()) {
                                    emitAssistantDelta(sessionKey, runId, visible, replace = true)
                                }
                            }
                        },
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

            val parsedOutput = LocalToolCallParser.parse(response)
            if (parsedOutput is LocalModelOutput.InvalidControl) {
                val safeMessage = "Local model emitted an invalid tool control frame; no tool was run."
                if (toolsAllowed) {
                    transcript.add("assistant control rejected: ${parsedOutput.error}")
                    transcript.add("system: Emit exactly one valid Lynk control frame for a tool, or answer in plain text without control markers.")
                    return@toolLoop
                }
                emitAssistant(sessionKey, runId, safeMessage)
                return safeMessage
            }
            val calls = when (parsedOutput) {
                is LocalModelOutput.ToolControl -> listOf(parsedOutput.call)
                is LocalModelOutput.AssistantText -> emptyList()
                is LocalModelOutput.InvalidControl -> error("handled above")
            }
            val assistantText = (parsedOutput as? LocalModelOutput.AssistantText)?.text ?: response
            if (calls.isEmpty()) {
                val finalText = cleanFinalText(assistantText.ifBlank { "I could not generate a response." })
                val shouldRetryPhoneTurn = phoneControlRequest && toolsAllowed &&
                    LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
                        response = finalText,
                        phoneToolExecuted = phoneToolExecuted,
                        phoneActionCount = phoneActionCount,
                        multiStepRequest = multiStepPhoneRequest
                    )
                if (shouldRetryPhoneTurn) {
                    if (noToolPhoneNudges < MAX_NO_TOOL_PHONE_NUDGES) {
                        noToolPhoneNudges += 1
                        transcript.add("assistant: $finalText")
                        transcript.add("system: You ended the turn without a phone tool call, but the Android phone-control request is not sufficiently verified. Continue with exactly one valid Lynk control frame for the next tool. For multi-step requests, do not stop after the first action; keep acting until every requested subgoal is visibly complete or blocked. Start with phone_observe if you do not have current screen context.")
                        return@toolLoop
                    }
                    val message = "BLOCKED: The local model stopped before completing the requested phone workflow."
                    emitAssistant(sessionKey, runId, message)
                    return message
                }
                emit(JSONObject()
                    .put("type", "chat.reasoning_clear")
                    .put("sessionKey", sessionKey)
                    .put("runId", runId))
                if (toolsAllowed) {
                    emitAssistant(sessionKey, runId, finalText)
                } else {
                    emitAssistantFinal(sessionKey, runId, finalText)
                }
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
                if (!toolAccess.allows(call.name)) {
                    val rejected = JSONObject()
                        .put("ok", false)
                        .put("error", "Tool ${call.name} is not authorized for this user request.")
                    transcript.add("assistant tool request rejected: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                    transcript.add("tool ${call.name} result: $rejected")
                    continue
                }
                if (!runtimeProfile.supportsImageInput && LocalToolSpecs.requiresImageInput(call.name)) {
                    val rejected = JSONObject()
                        .put("ok", false)
                        .put("error", "Tool ${call.name} requires image input, which is not supported by the selected local model.")
                    transcript.add("assistant tool request rejected: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                    transcript.add("tool ${call.name} result: $rejected")
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
                val result = executeAndRecordTool(sessionKey, runId, round, call, transcript, runtimeProfile)
                if (LocalToolPolicy.isPhoneTool(call.name)) {
                    phoneToolExecuted = true
                    if (LocalPhoneControlTurnPolicy.isPhoneActionTool(call.name)) {
                        phoneActionCount += 1
                    }
                }
                if (runtimeProfile.supportsImageInput) {
                    result.optString("screenshotPath").takeIf { it.isNotBlank() }?.let { path ->
                        latestScreenshotPath = path
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
            emitAssistantDelta(
                sessionKey = sessionKey,
                runId = runId,
                text = part,
                replace = index == 0
            )
        }
        emitAssistantFinal(sessionKey, runId, cleaned)
    }

    private fun emitAssistantDelta(sessionKey: String, runId: String, text: String, replace: Boolean) {
        emit(JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("delta", text)
            .put("replace", replace))
    }

    private fun emitAssistantFinal(sessionKey: String, runId: String, text: String) {
        emit(JSONObject()
            .put("type", "chat.final")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("text", cleanFinalText(text)))
    }

    private suspend fun executeAndRecordTool(
        sessionKey: String,
        runId: String,
        round: Int,
        call: LocalToolCall,
        transcript: MutableList<String>,
        runtimeProfile: LocalModelRuntimeProfile
    ): JSONObject {
        Log.i(TAG, "local turn $runId executing tool=${call.name} args=${call.args}")
        val eventId = "local_tool_${UUID.randomUUID()}"
        emit(toolEvent(sessionKey, runId, eventId, call, "running", "Running ${call.name}", null, null))
        val requestOwner = LocalPhoneCommandOwner.id(sessionKey, runId)
        val result = try {
            tools.execute(call, requestOwner)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            JSONObject().put("ok", false).put("error", error.message ?: error.toString())
        }
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
        transcript.add("tool ${call.name} result: ${transcriptToolResult(call, result, runtimeProfile)}")
        return result
    }

    private fun transcriptToolResult(
        call: LocalToolCall,
        result: JSONObject,
        runtimeProfile: LocalModelRuntimeProfile
    ): String {
        return if (call.name == "local_read_skill") {
            result.toString().take(MAX_SKILL_RESULT_CHARS)
        } else if (LocalToolPolicy.isPhoneTool(call.name)) {
            compactPhoneToolResult(result, includeScreenshotPath = runtimeProfile.supportsImageInput).toString()
        } else {
            result.toString().take(MAX_GENERIC_TOOL_RESULT_CHARS)
        }
    }

    private fun compactPhoneToolResult(result: JSONObject, includeScreenshotPath: Boolean): JSONObject {
        val compact = JSONObject()
            .put("ok", result.optBoolean("ok", false))
            .put("error", result.optString("error").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        result.optString("approvalCapability").takeIf { it.isNotBlank() }?.let { compact.put("approvalCapability", it) }
        result.optString("approvedAction").takeIf { it.isNotBlank() }?.let { compact.put("approvedAction", it) }
        if (result.has("approvalExpiresAtMs") && !result.isNull("approvalExpiresAtMs")) {
            compact.put("approvalExpiresAtMs", result.optLong("approvalExpiresAtMs"))
        }
        val observation = result.optJSONObject("observation")
        if (observation != null) {
            compact.put("observation", compactObservation(observation))
        }
        if (includeScreenshotPath) {
            result.optString("screenshotPath").takeIf { it.isNotBlank() }?.let { path ->
                compact.put("screenshotPath", path)
            }
        }
        return compact
    }

    private fun compactObservation(observation: JSONObject): JSONObject {
        return JSONObject()
            .put("package", observation.optString("package"))
            .put("activity", observation.optString("activity"))
            .put("display", observation.optJSONObject("display") ?: JSONObject.NULL)
            .put("screenSummary", observation.optString("screenSummary"))
            .put("nodes", compactNodes(observation.optJSONArray("nodes")))
    }

    private fun compactNodes(nodes: JSONArray?): JSONArray {
        val compact = JSONArray()
        if (nodes == null) return compact
        var index = 0
        while (index < nodes.length() && compact.length() < MAX_TRANSCRIPT_NODES) {
            val node = nodes.optJSONObject(index)
            if (node != null && isUsefulTranscriptNode(node)) {
                compact.put(JSONObject()
                    .put("id", node.optString("id"))
                    .put("text", node.optString("text"))
                    .put("contentDescription", node.optString("contentDescription"))
                    .put("stateDescription", node.optString("stateDescription"))
                    .put("className", node.optString("className"))
                    .put("clickable", node.optBoolean("clickable", false))
                    .put("scrollable", node.optBoolean("scrollable", false))
                    .put("editable", node.optBoolean("editable", false))
                    .put("focused", node.optBoolean("focused", false))
                    .put("bounds", node.optJSONArray("bounds") ?: JSONArray()))
            }
            index += 1
        }
        return compact
    }

    private fun isUsefulTranscriptNode(node: JSONObject): Boolean {
        return node.optString("text").isNotBlank() ||
            node.optString("contentDescription").isNotBlank() ||
            node.optString("stateDescription").isNotBlank() ||
            node.optBoolean("clickable", false) ||
            node.optBoolean("scrollable", false) ||
            node.optBoolean("editable", false) ||
            node.optBoolean("focused", false)
    }

    private fun state(
        sessionKey: String,
        runId: String?,
        isRunning: Boolean,
        status: String,
        taskKind: String? = null
    ): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("taskKind", taskKind)
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

    private fun cleanFinalText(text: String): String {
        return LocalResponseTextNormalizer.normalize(text)
    }

    companion object {
        private const val TAG = "LocalAgentController"
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_CONSECUTIVE_OBSERVES = 2
        private const val MAX_NO_TOOL_PHONE_NUDGES = 2
        private const val MAX_TRANSCRIPT_NODES = 40
        private const val MAX_SKILL_RESULT_CHARS = 8_000
        private const val MAX_GENERIC_TOOL_RESULT_CHARS = 4_000
        private const val MODEL_RESPONSE_TIMEOUT_MS = 180_000L
    }
}
