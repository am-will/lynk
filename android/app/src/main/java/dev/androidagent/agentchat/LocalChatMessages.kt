package dev.androidagent.agentchat

import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions
import dev.androidagent.chat.ChatAttachmentPreviewJson
import dev.androidagent.localmodel.LocalChatMessage
import dev.androidagent.localmodel.LocalChatSession
import dev.androidagent.localmodel.LocalPromptBuilder
import dev.androidagent.localmodel.LocalResponseTextNormalizer
import dev.androidagent.localmodel.LocalModelRuntimeProfile
import org.json.JSONArray
import org.json.JSONObject

object LocalChatMessages {
    fun models(runtimeProfile: LocalModelRuntimeProfile): JSONObject =
        JSONObject()
            .put("type", "chat.models")
            .put("source", "local")
            .put("models", JSONArray().put(JSONObject()
                .put("id", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                .put("label", "Local model")
                .put("provider", "android")
                .put("harnessId", AgentConfig.HARNESS_LOCAL)
                .put("harnessLabel", "Local")
                .put("modelId", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                .put("contextWindow", runtimeProfile.effectiveContextTokens)
                .put("available", true)))
            .put("reasoningOptions", JSONArray()
                .put(JSONObject().put("id", "low").put("label", "low"))
                .put(JSONObject().put("id", "medium").put("label", "medium"))
                .put(JSONObject().put("id", "high").put("label", "high")))

    fun tools(sessionKey: String, toolDescriptions: JSONArray): JSONObject =
        JSONObject()
            .put("type", "chat.tools")
            .put("sessionKey", sessionKey)
            .put("tools", toolDescriptions)

    fun sessions(
        selectedKey: String,
        sessions: List<LocalChatSession>,
        config: AgentConfig,
        runtimeProfile: LocalModelRuntimeProfile,
        toolDescriptionsJson: String
    ): JSONObject =
        JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", selectedKey)
            .put("sessions", JSONArray().also { array ->
                sessions.forEach { session ->
                    val usage = tokenUsage(session, config, runtimeProfile, toolDescriptionsJson)
                    array.put(JSONObject()
                        .put("key", session.key)
                        .put("label", session.label)
                        .put("displayName", session.label)
                        .put("updatedAt", session.updatedAt)
                        .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                        .put("modelProvider", "android")
                        .put("harnessId", AgentConfig.HARNESS_LOCAL)
                        .put("harnessLabel", "Local")
                        .put("inputTokens", usage.inputTokens)
                        .put("outputTokens", usage.outputTokens)
                        .put("totalTokens", usage.totalTokens)
                        .put("contextTokens", usage.contextTokens)
                        .put("thinkingLevel", config.reasoningEffort))
                }
            })

    fun usage(
        session: LocalChatSession,
        config: AgentConfig,
        runtimeProfile: LocalModelRuntimeProfile,
        toolDescriptionsJson: String
    ): JSONObject {
        val usage = tokenUsage(session, config, runtimeProfile, toolDescriptionsJson)
        return JSONObject()
            .put("type", "chat.usage")
            .put("sessionKey", session.key)
            .put("usage", JSONObject()
                .put("inputTokens", usage.inputTokens)
                .put("outputTokens", usage.outputTokens)
                .put("totalTokens", usage.totalTokens)
                .put("contextTokens", usage.contextTokens))
    }

    fun history(session: LocalChatSession): JSONObject =
        JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", session.key)
            .put("messages", JSONArray().also { array ->
                session.messages.forEach { message ->
                    array.put(messageBody(message))
                }
            })

    fun chatMessage(sessionKey: String, message: LocalChatMessage): JSONObject =
        JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", sessionKey)
            .put("message", messageBody(message))

    fun state(
        config: AgentConfig,
        sessionKey: String,
        runId: String?,
        isRunning: Boolean,
        status: String,
        model: String = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
        reasoningEffort: String = config.reasoningEffort,
        taskKind: String? = null
    ): JSONObject =
        JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("isRunning", isRunning)
            .put("status", status)
            .put("taskKind", taskKind)
            .put("model", model)
            .put("reasoningEffort", reasoningEffort)

    fun error(sessionKey: String, message: String, runId: String? = null): JSONObject =
        JSONObject()
            .put("type", "chat.error")
            .put("sessionKey", sessionKey)
            .put("runId", runId)
            .put("message", message)

    fun replyAvailable(session: LocalChatSession, runId: String, status: String, text: String): JSONObject =
        JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", session.key)
            .put("runId", runId)
            .put("status", status)
            .put("textPreview", LocalResponseTextNormalizer.normalize(text).take(180))
            .put("sessionLabel", session.label)
            .put("sessionDisplayName", session.label)
            .put("harnessId", AgentConfig.HARNESS_LOCAL)
            .put("harnessLabel", "Local")
            .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)

    private fun messageBody(message: LocalChatMessage): JSONObject =
        JSONObject()
            .put("id", message.id)
            .put("role", message.role)
            .put("text", if (message.role == "assistant") {
                LocalResponseTextNormalizer.normalize(message.text)
            } else {
                message.text
            })
            .put("timestamp", message.timestamp)
            .put("attachments", ChatAttachmentPreviewJson.toJsonArray(message.attachments))

    private fun tokenUsage(
        session: LocalChatSession,
        config: AgentConfig,
        runtimeProfile: LocalModelRuntimeProfile,
        toolDescriptionsJson: String
    ): LocalUsage {
        val promptTokens = estimateTokenCount(LocalPromptBuilder.systemPrompt(
            basePrompt = config.systemPrompt,
            toolsAllowed = true,
            toolDescriptionsJson = toolDescriptionsJson
        ))
        var inputTokens = promptTokens
        var outputTokens = 0L
        session.messages.forEach { message ->
            val tokens = estimateTokenCount(message.text)
            if (message.role == "assistant") {
                outputTokens += tokens
            } else {
                inputTokens += tokens
            }
        }
        return LocalUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            contextTokens = runtimeProfile.effectiveContextTokens.toLong()
        )
    }

    private fun estimateTokenCount(text: String): Long {
        if (text.isBlank()) return 0L
        return ((text.length + 3) / 4).toLong()
    }

    private data class LocalUsage(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val contextTokens: Long
    )
}
