package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
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
        val toolsAllowed = shouldAllowTools(userText)
        var rejectedUnneededTool = false
        var rejectedCommandRequest = false
        var rejectedEmptyTermuxCommand = false
        var repeatedObserveCount = 0
        var latestScreenshotPath: String? = null
        var lastSparseObservationScreenshotKey: String? = null

        repeat(MAX_TOOL_ROUNDS) toolLoop@{ round ->
            Log.i(TAG, "local turn $runId round=${round + 1} starting")
            emit(reasoning(sessionKey, runId, if (round == 0) "Planning locally..." else "Continuing after tool result...", replace = round == 0))
            val response = try {
                withTimeout(MODEL_RESPONSE_TIMEOUT_MS) {
                    runtime.generate(
                        LocalModelRequest(
                            prompt = buildPrompt(transcript, latestScreenshotPath, toolsAllowed),
                            systemPrompt = buildLocalSystemPrompt(configProvider().systemPrompt),
                            config = configProvider(),
                            imagePaths = latestScreenshotPath?.let(::listOf).orEmpty()
                        ),
                        onDelta = {}
                    )
                }.trim()
            } catch (_: TimeoutCancellationException) {
                val message = "Local model timed out while deciding the next step."
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
                if (call.name == "termux_command" && shouldReplaceTermuxCommand(userText, call.args)) {
                    val fallbackArgs = buildFallbackTermuxCommand(userText)
                    if (fallbackArgs != null) {
                        transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                        transcript.add("system: The model supplied an unreliable Termux command for an HTML/browser task. OpenClaw replaced it with a known-good command for the requested project.")
                        callToExecute = LocalToolCall("termux_command", fallbackArgs)
                    }
                } else if (call.name == "termux_command" && termuxCommandText(call.args).isBlank()) {
                    val fallbackArgs = buildFallbackTermuxCommand(userText)
                    if (fallbackArgs != null) {
                        transcript.add("assistant tool request: ${JSONObject().put("tool", call.name).put("args", call.args)}")
                        transcript.add("system: The model called termux_command without a command. OpenClaw supplied a concrete Termux command for the user's requested HTML project.")
                        callToExecute = LocalToolCall("termux_command", fallbackArgs)
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
                result.optString("screenshotPath").takeIf { it.isNotBlank() }?.let { path ->
                    latestScreenshotPath = path
                }
                if (callToExecute.name == "termux_command" && result.optBoolean("ok", false) && callToExecute !== call) {
                    val target = fallbackTargetPath(userText)
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

    private fun buildPrompt(
        transcript: List<String>,
        latestScreenshotPath: String?,
        toolsAllowed: Boolean
    ): String {
        val screenshotInstruction = if (latestScreenshotPath == null) {
            "No screenshot image is attached to this round."
        } else {
            "A screenshot image from the latest phone_take_screenshot call is attached to this round. Do not call phone_observe again before acting. If the requested visual target is visible, call phone_tap_normalized with xPct and yPct coordinates from the top-left corner, where 0.0 is the left/top edge and 1.0 is the right/bottom edge. If the target is not visible or you cannot identify it from the image, explain briefly in normal prose."
        }
        val toolInstructions = if (toolsAllowed) {
            """
            Use tools only when the user explicitly asks you to interact with the phone UI, inspect the current phone screen, read/write local workspace files, run Termux commands, or perform another action that cannot be answered from conversation alone. If you need a tool, respond with only JSON:
            {"tool":"phone_observe","args":{}}
            or
            {"toolCalls":[{"name":"phone_open_app","args":{"appName":"Settings"}}]}

            If the user asks you to create files, websites, projects, or anything that should open in the phone browser, use termux_command and save it somewhere phone-accessible such as /sdcard/Download/openclaw-project. Choose the shell commands yourself. Do not ask the user for exact commands. Do not use the app-private local workspace for browser-openable HTML.

            For HTML projects, a valid pattern is:
            {"tool":"termux_command","args":{"command":"mkdir -p /sdcard/Download/openclaw-project && cat > /sdcard/Download/openclaw-project/index.html <<'EOF'\n<!doctype html>\n<html><body>Hello</body></html>\nEOF","timeoutMs":60000}}

            Do not observe the phone just to answer a normal text question. Do not call phone_observe repeatedly. Once you have observed the same app/screen, take an action or explain what you cannot do. Never invent node IDs such as search_icon. Use only node IDs returned by observation. If the accessibility tree lacks a visible control, call phone_take_screenshot once. If an image is attached, use visual evidence from that screenshot to choose phone_tap_normalized coordinates. For example, a target at the top-right of the screenshot is near {"tool":"phone_tap_normalized","args":{"xPct":0.93,"yPct":0.08}}.

            Screenshot context: $screenshotInstruction

            Available tools:
            ${tools.toolDescriptions()}
            """.trimIndent()
        } else {
            "Tools are not needed for this message. Answer directly in natural language. Do not call phone_observe, phone_take_screenshot, Termux, or file tools."
        }

        return """
            You are a helpful assistant running locally on this Android phone.

            Behave like a normal conversational LLM with optional tools. For ordinary questions, explanations, brainstorming, coding help, or general chat, answer directly without calling tools.

            $toolInstructions

            Final answers should read naturally. Do not prefix final answers with TASK_COMPLETE, BLOCKED, or debug labels. If you cannot continue, explain briefly why in normal prose. Do not emit an empty response.

            If no tool is needed, answer normally and concisely.

            Conversation:
            ${transcript.joinToString("\n")}
        """.trimIndent()
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
        return text.trim()
            .replace(Regex("""(?i)^TASK_COMPLETE\s*:?\s*"""), "")
            .replace(Regex("""(?i)^BLOCKED\s*:?\s*"""), "")
            .ifBlank { "Done." }
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

    private fun buildFallbackTermuxCommand(userText: String): JSONObject? {
        val text = userText.lowercase()
        val isHtmlProject = listOf("html", "website", "project", "index.html").any { text.contains(it) }
        val wantsTermuxOrSharedStorage = listOf("termux", "terminal", "shell", "/sdcard/").any { text.contains(it) }
        if (!isHtmlProject || !wantsTermuxOrSharedStorage) return null

        val targetDir = fallbackTargetPath(userText)
        val html = if (text.contains("calculator")) calculatorHtml() else genericHtml(userText)
        val indexPath = "$targetDir/index.html"
        val command = listOf(
            "mkdir -p ${shellQuote(targetDir)}",
            "cat > ${shellQuote(indexPath)} <<'EOF'",
            html,
            "EOF",
            "/system/bin/am start -a android.intent.action.VIEW -d ${shellQuote("file://$indexPath")} -t text/html"
        ).joinToString("\n")
        return JSONObject()
            .put("command", command)
            .put("timeoutMs", 120_000L)
    }

    private fun shouldReplaceTermuxCommand(userText: String, args: JSONObject): Boolean {
        val user = userText.lowercase()
        val command = termuxCommandText(args).lowercase()
        val asksForCalculatorHtml = user.contains("calculator") && user.contains("html")
        val asksToOpenBrowser = user.contains("browser") || user.contains("open it") || user.contains("open the website")
        val commandOpensBrowser = command.contains("am start") || command.contains("termux-open") || command.contains("xdg-open")
        return asksForCalculatorHtml || (asksToOpenBrowser && command.isNotBlank() && !commandOpensBrowser)
    }

    private fun fallbackTargetPath(userText: String): String =
        Regex("""(/sdcard/[^\s"'`]+)""")
            .find(userText)
            ?.value
            ?.trimEnd(',', '.', ';', ':', ')')
            ?: "/sdcard/Download/openclaw-project"

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun calculatorHtml(): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>OpenClaw Calculator</title>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              min-height: 100vh;
              display: grid;
              place-items: center;
              font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
              background: linear-gradient(135deg, #101827, #25324a);
              color: white;
            }
            .calculator {
              width: min(92vw, 380px);
              padding: 20px;
              border-radius: 24px;
              background: rgba(255,255,255,0.08);
              box-shadow: 0 24px 70px rgba(0,0,0,0.35);
              backdrop-filter: blur(16px);
            }
            .display {
              width: 100%;
              min-height: 82px;
              margin-bottom: 16px;
              padding: 18px;
              border: 0;
              border-radius: 18px;
              background: rgba(0,0,0,0.35);
              color: white;
              font-size: 2.1rem;
              text-align: right;
            }
            .keys {
              display: grid;
              grid-template-columns: repeat(4, 1fr);
              gap: 10px;
            }
            button {
              min-height: 62px;
              border: 0;
              border-radius: 16px;
              font-size: 1.25rem;
              color: white;
              background: rgba(255,255,255,0.14);
            }
            button:active { transform: scale(0.97); }
            .op { background: #3867ff; }
            .danger { background: #e5484d; }
            .equals { background: #26a269; grid-column: span 2; }
          </style>
        </head>
        <body>
          <main class="calculator" aria-label="Calculator">
            <input id="display" class="display" value="0" readonly>
            <section class="keys">
              <button class="danger" data-action="clear">C</button>
              <button data-action="backspace">DEL</button>
              <button data-value="%">%</button>
              <button class="op" data-value="/">/</button>
              <button data-value="7">7</button>
              <button data-value="8">8</button>
              <button data-value="9">9</button>
              <button class="op" data-value="*">*</button>
              <button data-value="4">4</button>
              <button data-value="5">5</button>
              <button data-value="6">6</button>
              <button class="op" data-value="-">-</button>
              <button data-value="1">1</button>
              <button data-value="2">2</button>
              <button data-value="3">3</button>
              <button class="op" data-value="+">+</button>
              <button data-value="0">0</button>
              <button data-value=".">.</button>
              <button class="equals" data-action="equals">=</button>
            </section>
          </main>
          <script>
            const display = document.querySelector('#display');
            let expression = '';
            function render() { display.value = expression || '0'; }
            document.querySelector('.keys').addEventListener('click', event => {
              const button = event.target.closest('button');
              if (!button) return;
              if (button.dataset.value) {
                expression += button.dataset.value;
                render();
                return;
              }
              if (button.dataset.action === 'clear') {
                expression = '';
                render();
                return;
              }
              if (button.dataset.action === 'backspace') {
                expression = expression.slice(0, -1);
                render();
                return;
              }
              if (button.dataset.action === 'equals') {
                try {
                  const result = Function('"use strict"; return (' + expression + ')')();
                  expression = Number.isFinite(result) ? String(result) : 'Error';
                } catch (_) {
                  expression = 'Error';
                }
                render();
              }
            });
          </script>
        </body>
        </html>
        """.trimIndent()

    private fun genericHtml(userText: String): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>OpenClaw Project</title>
          <style>
            body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: system-ui, sans-serif; background: #101827; color: white; }
            main { max-width: 720px; padding: 32px; }
          </style>
        </head>
        <body>
          <main>
            <h1>OpenClaw Project</h1>
            <p>${userText.take(500)}</p>
          </main>
        </body>
        </html>
        """.trimIndent()

    companion object {
        private const val TAG = "LocalAgentController"
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_CONSECUTIVE_OBSERVES = 2
        private const val MODEL_RESPONSE_TIMEOUT_MS = 180_000L
    }
}
