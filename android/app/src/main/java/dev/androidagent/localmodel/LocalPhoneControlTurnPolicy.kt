package dev.androidagent.localmodel

internal object LocalPhoneControlTurnPolicy {
    fun isMultiStepRequest(userText: String): Boolean {
        val text = userText.lowercase()
        return Regex("""\b(first|second|third|next|then|after that|finally)\b""").containsMatchIn(text) ||
            Regex("""\b\d+[.)]\s+\S+""").containsMatchIn(text) ||
            text.contains("multi-step") ||
            text.contains("multi step") ||
            text.contains("workflow") ||
            text.contains(";") ||
            text.contains(" and then ") ||
            text.contains(", then ") ||
            listOf(
                " and tap ",
                " and click ",
                " and press ",
                " and type ",
                " and search ",
                " and open ",
                " and scroll ",
                " and send ",
                " and turn ",
                " and enable ",
                " and disable ",
                " and set ",
                " and check "
            )
                .any { text.contains(it) }
    }

    fun isPhoneActionTool(name: String): Boolean =
        name !in PASSIVE_PHONE_TOOLS

    fun shouldRetryNoToolResponse(
        response: String,
        phoneToolExecuted: Boolean,
        phoneActionCount: Int,
        multiStepRequest: Boolean
    ): Boolean {
        val text = response.trim()
        if (text.isBlank()) return true
        if (isBlockedPhoneResponse(text)) return false
        if (looksLikeUnfinishedTerminalResponse(text)) return true
        if (multiStepRequest && phoneActionCount < MIN_MULTI_STEP_PHONE_ACTIONS) return true
        if (isTerminalPhoneResponse(text)) return false
        return !phoneToolExecuted || looksLikeUnexecutedPlan(text)
    }

    private fun isTerminalPhoneResponse(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.startsWith("task_complete:") ||
            normalized.startsWith("blocked:") ||
            normalized.startsWith("done.") ||
            normalized.startsWith("done,") ||
            normalized.startsWith("i'm blocked") ||
            normalized.startsWith("i am blocked")
    }

    private fun isBlockedPhoneResponse(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.startsWith("blocked:") ||
            normalized.startsWith("i'm blocked") ||
            normalized.startsWith("i am blocked")
    }

    private fun looksLikeUnexecutedPlan(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf(
            "i'm going to",
            "i am going to",
            "i will",
            "i'll",
            "next, i",
            "first, i",
            "then i",
            "i need to",
            "i should",
            "let me",
            "i can start by",
            "i'll start by"
        ).any { normalized.contains(it) }
    }

    private fun looksLikeUnfinishedTerminalResponse(text: String): Boolean {
        val normalized = text.lowercase()
        if (!isTerminalPhoneResponse(text)) return false
        return listOf(
            "i need to",
            "i still need to",
            "now i need",
            "next i need",
            "i have to",
            "i will",
            "i'll",
            "need to find",
            "need to tap",
            "need to type",
            "need to send",
            "not yet",
            "still need"
        ).any { normalized.contains(it) }
    }

    private val PASSIVE_PHONE_TOOLS = setOf(
        "phone_observe",
        "phone_take_screenshot",
        "phone_wait"
    )

    private const val MIN_MULTI_STEP_PHONE_ACTIONS = 2
}
