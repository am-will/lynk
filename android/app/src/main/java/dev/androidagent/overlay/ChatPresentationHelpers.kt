package dev.androidagent.overlay

import dev.androidagent.AgentModelOptions
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatSessionRow

object ChatPresentationHelpers {
    fun isAgentInternalTool(id: String, label: String?): Boolean {
        val needle = (label ?: id).lowercase().trim()
        val rawId = id.lowercase().trim()
        val hiddenExact = setOf(
            "apply_patch", "apply-patch", "applypatch",
            "exec",
            "edit",
            "process",
            "read",
            "session_history", "session-history", "sessionhistory",
            "send",
            "status",
            "list",
            "spawn",
            "session_send", "session-send", "sessionsend",
            "session_status", "session-status", "sessionstatus",
            "session_list", "session-list", "sessionlist",
            "session_spawn", "session-spawn", "sessionspawn",
            "update_plan", "update-plan", "updateplan",
            "web_fetch", "web-fetch", "webfetch",
            "web_search", "web-search", "websearch",
            "subagent", "sub_agent", "sub-agent",
            "subagents", "sub_agents", "sub-agents"
        )
        if (rawId in hiddenExact || needle in hiddenExact) return true
        val hiddenPrefixes = listOf(
            "apply patch",
            "apply_patch",
            "session history",
            "session_history",
            "session send",
            "session_send",
            "session status",
            "session_status",
            "session list",
            "session_list",
            "session spawn",
            "session_spawn",
            "update plan",
            "update_plan",
            "web fetch",
            "web_fetch",
            "web search",
            "web_search",
            "subagent",
            "sub_agent",
            "sub-agent"
        )
        return hiddenPrefixes.any { needle.startsWith(it) || rawId.startsWith(it) }
    }

    fun mergeModelOptions(
        gatewayModels: List<ChatModelOption>,
        localLiteRtAvailable: Boolean
    ): List<ChatModelOption> {
        val byId = linkedMapOf<String, ChatModelOption>()
        AgentModelOptions.models.forEach { local ->
            byId[local.id] = ChatModelOption(
                id = local.id,
                label = local.label,
                provider = null,
                contextWindow = null,
                available = true
            )
        }
        gatewayModels.forEach { remote ->
            byId[remote.id] = remote
        }
        if (localLiteRtAvailable) {
            byId[AgentModelOptions.LOCAL_LITERT_MODEL_ID] = ChatModelOption(
                id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                label = "Local LiteRT-LM",
                provider = "android",
                contextWindow = null,
                available = true
            )
        } else {
            byId.remove(AgentModelOptions.LOCAL_LITERT_MODEL_ID)
        }
        return byId.values.toList()
    }

    fun selectedModelId(selectedModel: String?, localLiteRtAvailable: Boolean): String {
        return if (selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !localLiteRtAvailable) {
            AgentModelOptions.models.firstOrNull()?.id.orEmpty()
        } else {
            selectedModel.orEmpty()
        }
    }

    fun formatModelLabel(
        model: String?,
        models: List<ChatModelOption>,
        localLiteRtAvailable: Boolean
    ): String {
        val raw = if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !localLiteRtAvailable) {
            AgentModelOptions.models.firstOrNull()?.id
        } else {
            model
        } ?: return "Model"
        val pretty = models.firstOrNull { it.id == raw }?.label
            ?: raw.substringAfter("/").ifBlank { raw }
        return if (pretty.startsWith("gpt-", ignoreCase = true)) pretty.drop(4) else pretty
    }

    fun formatReasoningLabel(reasoning: String?): String {
        val value = reasoning?.takeIf { it.isNotBlank() } ?: return "Reason"
        if (value.equals("medium", ignoreCase = true)) return "Med"
        return value.replaceFirstChar { it.uppercase() }
    }

    fun sessionLabel(session: ChatSessionRow): String {
        return session.displayName ?: session.label ?: session.sessionId ?: session.key.substringAfterLast(":")
    }

    fun normalizedVerboseLevel(level: String?): String {
        return when (level?.lowercase()?.trim()) {
            "on", "full" -> level.lowercase().trim()
            "high", "true" -> "on"
            else -> "off"
        }
    }

    fun nextVerboseLevel(current: String): String {
        return when (current) {
            "off" -> "on"
            "on" -> "full"
            else -> "off"
        }
    }
}
