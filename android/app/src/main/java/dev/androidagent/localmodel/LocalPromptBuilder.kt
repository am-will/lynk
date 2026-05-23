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
            - If you need a tool, respond with only JSON such as {"tool":"phone_observe","args":{}} or {"toolCalls":[{"name":"phone_open_app","args":{"appName":"Settings"}}]}.
            - For Android phone-control tasks, first call {"tool":"local_read_skill","args":{"name":"android-control"}} and follow that skill before any phone_* tool.
            - If the user asks you to create files, websites, projects, or anything that should open in the phone browser, use termux_command and save it somewhere phone-accessible such as /sdcard/Download/openclaw-project. Choose the shell commands yourself.
            - Do not observe the phone just to answer a normal text question. Do not call phone_observe repeatedly.
            - Never invent node IDs. Use only node IDs returned by observation.

            Available tools:
            $toolDescriptionsJson
            """.trimIndent()
        } else {
            "Tools are not needed for this message. Answer directly in natural language. Do not call phone_observe, phone_take_screenshot, Termux, or file tools."
        }

        return """
            $basePrompt

            Local mode override:
            - Behave like a normal conversational LLM with optional tools.
            - For ordinary questions, explanations, brainstorming, coding help, or general chat, answer directly without calling tools.
            - For file/project creation requests, decide the commands yourself. Do not ask the user to provide commands.
            - For HTML or files that should open in the browser, use Termux/shared storage, not the app-private local workspace.
            - Final answers should read naturally. Do not prefix final answers with TASK_COMPLETE, BLOCKED, or debug labels. Do not emit an empty response.

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
