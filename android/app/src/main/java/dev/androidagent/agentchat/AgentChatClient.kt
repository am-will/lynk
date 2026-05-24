package dev.androidagent.agentchat

import org.json.JSONObject

enum class ChatSendDelivery(val key: String) {
    Normal("normal"),
    Queue("queue"),
    Steer("steer")
}

interface AgentChatClient {
    fun open(sessionKey: String? = null): Boolean
    fun send(
        text: String,
        sessionKey: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        delivery: ChatSendDelivery = ChatSendDelivery.Normal
    ): Boolean
    fun stop(sessionKey: String? = null, runId: String? = null, reason: String = "Stopped from Android chat")
    fun selectSession(sessionKey: String)
    fun newSession(label: String? = null, model: String? = null, workspacePath: String? = null)
    fun setModel(sessionKey: String?, model: String)
    fun setReasoning(sessionKey: String?, reasoningEffort: String)
    fun controlCommand(command: String, args: JSONObject = JSONObject())
    fun close()
}
