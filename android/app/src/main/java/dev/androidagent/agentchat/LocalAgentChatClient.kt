package dev.androidagent.agentchat

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.chat.StoredChatAttachment
import dev.androidagent.localmodel.LiteRtLmRuntime
import dev.androidagent.localmodel.LocalModelRuntime
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

class LocalAgentChatClient(
    context: Context,
    scope: CoroutineScope,
    commandExecutor: AccessibilityCommandExecutor,
    configProvider: () -> AgentConfig,
    onStatus: (String, String) -> Unit,
    onChatMessage: (JSONObject) -> Unit,
    runtime: LocalModelRuntime = LiteRtLmRuntime(context.applicationContext)
) : AgentChatClient {
    private val coordinator = LocalAgentTurnCoordinator(
        context = context,
        scope = scope,
        commandExecutor = commandExecutor,
        configProvider = configProvider,
        onStatus = onStatus,
        onChatMessage = onChatMessage,
        runtime = runtime
    )

    override fun open(sessionKey: String?): Boolean = coordinator.open(sessionKey)

    override fun send(
        text: String,
        sessionKey: String?,
        model: String?,
        reasoningEffort: String?,
        delivery: ChatSendDelivery,
        attachments: List<StoredChatAttachment>
    ): Boolean =
        coordinator.startTurn(
            LocalTurnRequest(
                text = text,
                sessionKey = sessionKey,
                attachments = attachments
            )
        )

    override fun stop(sessionKey: String?, runId: String?, reason: String) {
        coordinator.stop(sessionKey, reason)
    }

    override fun selectSession(sessionKey: String) {
        coordinator.selectSession(sessionKey)
    }

    override fun newSession(label: String?, model: String?, workspacePath: String?, createWorkspaceIfMissing: Boolean) {
        coordinator.newSession(label)
    }

    override fun setModel(sessionKey: String?, model: String) {
        coordinator.setModel(sessionKey, model)
    }

    override fun setReasoning(sessionKey: String?, reasoningEffort: String) {
        coordinator.setReasoning(sessionKey, reasoningEffort)
    }

    override fun controlCommand(command: String, args: JSONObject) {
        coordinator.controlCommand(command)
    }

    override fun close() {
        coordinator.close()
    }
}
