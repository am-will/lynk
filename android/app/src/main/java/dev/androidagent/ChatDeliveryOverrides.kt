package dev.androidagent

import dev.androidagent.agentchat.ChatSendDelivery

internal data class ChatDeliveryOverride(
    val delivery: ChatSendDelivery,
    val text: String
)

internal fun parseChatDeliveryOverride(text: String): ChatDeliveryOverride? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) {
        return null
    }
    val body = trimmed.removePrefix("/").trimStart()
    val command = body.substringBefore(' ').lowercase()
    val prompt = body.substringAfter(' ', missingDelimiterValue = "").trim()
    if (prompt.isBlank()) {
        return null
    }
    val delivery = when (command) {
        "queue" -> ChatSendDelivery.Queue
        "steer" -> ChatSendDelivery.Steer
        else -> return null
    }
    val unquotedPrompt = unquotePrompt(prompt)
    if (unquotedPrompt.isBlank()) {
        return null
    }
    return ChatDeliveryOverride(delivery, unquotedPrompt)
}

private fun unquotePrompt(prompt: String): String {
    if (prompt.length < 2) {
        return prompt
    }
    val first = prompt.first()
    val last = prompt.last()
    return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        prompt.substring(1, prompt.length - 1).trim()
    } else {
        prompt
    }
}
