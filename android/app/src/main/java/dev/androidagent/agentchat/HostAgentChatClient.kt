package dev.androidagent.agentchat

import dev.androidagent.chat.ChatAttachment
import dev.androidagent.net.PhoneWebSocketClient
import org.json.JSONObject

class HostAgentChatClient(
    private val webSocketClient: PhoneWebSocketClient
) : AgentChatClient {
    override fun open(sessionKey: String?): Boolean =
        webSocketClient.sendChatOpen(sessionKey)

    override fun send(
        text: String,
        sessionKey: String?,
        model: String?,
        reasoningEffort: String?,
        delivery: ChatSendDelivery,
        attachments: List<ChatAttachment>
    ): Boolean =
        webSocketClient.sendChatMessage(text, sessionKey, model, reasoningEffort, delivery, attachments)

    override fun stop(sessionKey: String?, runId: String?, reason: String) {
        webSocketClient.sendChatStop(sessionKey, runId, reason)
    }

    override fun selectSession(sessionKey: String) {
        webSocketClient.sendChatSelectSession(sessionKey)
    }

    override fun newSession(label: String?, model: String?, workspacePath: String?, createWorkspaceIfMissing: Boolean) {
        webSocketClient.sendChatNewSession(label, model, workspacePath, createWorkspaceIfMissing)
    }

    override fun setModel(sessionKey: String?, model: String) {
        webSocketClient.sendChatSetModel(sessionKey, model)
    }

    override fun setReasoning(sessionKey: String?, reasoningEffort: String) {
        webSocketClient.sendChatSetReasoning(sessionKey, reasoningEffort)
    }

    override fun controlCommand(command: String, args: JSONObject) {
        webSocketClient.sendChatControlCommand(command, args)
    }

    override fun close() = Unit
}
