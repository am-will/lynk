package dev.androidagent.localmodel

internal object LocalPromptBuilder {
    fun systemPrompt(
        basePrompt: String,
        toolsAllowed: Boolean,
        toolDescriptionsJson: String
    ): String {
        val toolPolicy = if (toolsAllowed) {
            """
            Tool mode:
            - Use tools only when the user asks you to interact with the phone UI, inspect the current phone screen, read/write local workspace files, run Termux commands, or perform another action that cannot be answered from conversation alone.
            - A tool request must be the entire response and contain exactly three concatenated parts: the literal opening marker <|lynk_control|>, one JSON object, and the literal closing marker <|/lynk_control|>. Never add prose, Markdown fences, or a second frame.
            - The JSON object must have exactly these keys: version (integer 1), type (string tool_call), tool (an available tool ID), and args (an object matching that tool's documented schema). Unknown or extra keys are rejected.
            - Non-executable shape illustration (the placeholder tool ID is intentionally invalid): {"version":1,"type":"tool_call","tool":"TOOL_ID","args":{}}
            - For Android phone-control tasks, first request local_read_skill with args containing name android-control, then follow the returned skill before any phone_* tool.
            - Sensitive phone actions, local_write_file, and termux_command require a prior phone_ask_user_confirmation request for the exact command and args. Copy the returned approvalCapability into the otherwise identical action args. Capabilities are owner-bound, short-lived, and single-use.
            - If the user explicitly asks you to create files, websites, projects, or anything that should open in the phone browser, request approval for the exact termux_command and save it somewhere phone-accessible such as /sdcard/Download/lynk-project. Choose the shell commands yourself.
            - Do not observe the phone just to answer a normal text question. Do not call phone_observe repeatedly.
            - Never invent node IDs. Use only node IDs returned by observation.

            Available tools:
            $toolDescriptionsJson
            """.trimIndent()
        } else {
            "Tools are not needed for this message. Answer directly in natural language. Do not call phone_observe, phone_take_screenshot, Termux, or file tools."
        }

        if (!toolsAllowed) {
            return """
                $basePrompt

                Local mode: Answer directly as a conversational assistant. Tools are not needed for this message. Do not emit tool-control markers.
            """.trimIndent()
        }

        return """
            $basePrompt

            Local mode override:
            - Behave like a normal conversational LLM with optional tools.
            - For ordinary questions, explanations, brainstorming, coding help, or general chat, answer directly without calling tools.
            - For file/project creation requests, decide the commands yourself. Do not ask the user to provide commands.
            - For HTML or files that should open in the browser, use Termux/shared storage, not the app-private local workspace.
            - Final answers should read naturally. Do not prefix final answers with TASK_COMPLETE, BLOCKED, or debug labels unless a loaded skill explicitly requires that format. Do not emit an empty response.

            $toolPolicy
        """.trimIndent()
    }

    fun roundPrompt(
        transcript: List<String>,
        latestScreenshotPath: String?
    ): String {
        val screenshotInstruction = if (latestScreenshotPath == null) {
            "No screenshot image is attached to this round."
        } else {
            "A screenshot image from the latest phone_take_screenshot call is attached to this round. If the requested visual target is visible, call phone_tap_normalized with xPct and yPct coordinates from the top-left corner, where 0.0 is the left/top edge and 1.0 is the right/bottom edge. If the target is not visible or you cannot identify it from the image, explain briefly in normal prose."
        }

        return """
            Screenshot context: $screenshotInstruction

            Conversation:
            ${transcript.joinToString("\n")}
        """.trimIndent()
    }

    fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length + 3) / 4
    }
}
