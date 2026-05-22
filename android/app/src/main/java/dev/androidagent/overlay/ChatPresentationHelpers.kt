package dev.androidagent.overlay

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import dev.androidagent.AgentModelOptions
import dev.androidagent.R
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatModelSource
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.ui.ThemeTokens

enum class ClientBrand {
    OpenClaw,
    Hermes,
    Codex,
    Local
}

data class ClientBrandPresentation(
    val brand: ClientBrand,
    val title: String,
    val logoRes: Int,
    val titleTreatment: BrandTitleTreatment = BrandTitleTreatment.PLAIN,
    val copyName: String = title
) {
    val copy: ClientBrandCopy
        get() = ClientBrandCopy(copyName)
}

enum class BrandTitleTreatment {
    PLAIN,
    OPENCLAW_ACCENT
}

data class ClientBrandCopy(val name: String) {
    val composerPlaceholder: String
        get() = "Message $name"
    val readyStatus: String
        get() = "$name chat ready."
    val loadingChatStatus: String
        get() = "Loading $name Chat"
    val thinkingStatus: String
        get() = "$name is thinking"
    val sentStatus: String
        get() = "Sent to $name"
    val finishedStatus: String
        get() = "$name finished"
    val failedStatus: String
        get() = "$name failed"
    val stopTurnDescription: String
        get() = "Stop $name turn"
    val defaultNotificationText: String
        get() = "$name chat is running"
    val emptyHistoryText: String
        get() = "$name is ready. Say something or pick a previous chat from the title menu."

    fun unreadReplies(count: Int): String {
        return "$count unread $name ${if (count == 1) "reply" else "replies"}"
    }

    fun repliedIn(label: String): String = "$name replied in $label"

    fun failedReplyFallback(): String = "$name failed. Tap to view details."
}

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
        localLiteRtAvailable: Boolean,
        localModels: List<ChatModelOption> = emptyList()
    ): List<ChatModelOption> {
        val byId = linkedMapOf<String, ChatModelOption>()
        AgentModelOptions.models.forEach { local ->
            byId[local.id] = ChatModelOption(
                id = local.id,
                label = local.label,
                provider = null,
                harnessId = "openclaw",
                harnessLabel = "OpenClaw",
                modelId = local.id,
                contextWindow = null,
                available = true,
                reasoningOptions = null,
                defaultReasoningEffort = null
            )
        }
        gatewayModels.forEach { remote ->
            byId[remote.id] = remote
        }
        if (localLiteRtAvailable) {
            byId[AgentModelOptions.LOCAL_LITERT_MODEL_ID] = localModels.firstOrNull { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?: ChatModelOption(
                    id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                    label = "Local LiteRT-LM",
                    provider = "android",
                    harnessId = "local",
                    harnessLabel = "Local",
                    modelId = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                )
        } else {
            byId.remove(AgentModelOptions.LOCAL_LITERT_MODEL_ID)
        }
        return byId.values.toList()
    }

    fun modelPickerOptions(state: ChatState, localLiteRtAvailable: Boolean): List<ChatModelOption> {
        val hostModels = state.hostModels.ifEmpty {
            if (state.modelSource == ChatModelSource.LOCAL) emptyList() else state.models
        }
        return mergeModelOptions(
            gatewayModels = hostModels,
            localLiteRtAvailable = localLiteRtAvailable,
            localModels = state.localModels
        )
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
            ?: raw.substringAfter(":").substringAfter("/").ifBlank { raw }
        return if (pretty.startsWith("gpt-", ignoreCase = true)) pretty.drop(4) else pretty
    }

    fun clientBrandPresentation(
        selectedModel: String?,
        models: List<ChatModelOption>,
        harnessId: String?,
        localLiteRtAvailable: Boolean
    ): ClientBrandPresentation {
        val brand = clientBrand(selectedModel, models, harnessId, localLiteRtAvailable)
        return when (brand) {
            ClientBrand.OpenClaw -> ClientBrandPresentation(
                brand = brand,
                title = "OpenClaw",
                logoRes = R.drawable.openclaw_bubble_logo,
                titleTreatment = BrandTitleTreatment.OPENCLAW_ACCENT
            )
            ClientBrand.Hermes -> ClientBrandPresentation(
                brand = brand,
                title = "Hermes",
                logoRes = R.drawable.hermes_nous_logo
            )
            ClientBrand.Codex -> ClientBrandPresentation(
                brand = brand,
                title = "Codex",
                logoRes = R.drawable.codex_bubble_logo
            )
            ClientBrand.Local -> ClientBrandPresentation(
                brand = brand,
                title = "LiteRT-LLM",
                logoRes = R.drawable.huggingface_logo,
                copyName = "LiteRT"
            )
        }
    }

    fun headerTitleText(presentation: ClientBrandPresentation, tokens: ThemeTokens): CharSequence {
        return when (presentation.titleTreatment) {
            BrandTitleTreatment.PLAIN -> presentation.title
            BrandTitleTreatment.OPENCLAW_ACCENT -> SpannableString(presentation.title).apply {
                setSpan(ForegroundColorSpan(tokens.danger), 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun headerTitleColor(presentation: ClientBrandPresentation, tokens: ThemeTokens): Int {
        return when (presentation.titleTreatment) {
            BrandTitleTreatment.PLAIN,
            BrandTitleTreatment.OPENCLAW_ACCENT -> tokens.primaryText
        }
    }

    fun chatStatusText(
        rawStatus: String?,
        isRunning: Boolean,
        presentation: ClientBrandPresentation
    ): String {
        val copy = presentation.copy
        val raw = rawStatus?.trim().orEmpty()
        if (raw.isBlank()) {
            return if (isRunning) copy.thinkingStatus else copy.readyStatus
        }
        return when (statusTemplate(raw)) {
            "loading {client} chat" -> copy.loadingChatStatus
            "{client} chat ready", "{client} ready" -> copy.readyStatus
            "thinking", "responding", "reasoning", "{client} is responding", "{client} is reasoning", "{client} is thinking", "{client} is working" -> copy.thinkingStatus
            "sent", "sent to {client}" -> copy.sentStatus
            "finished", "{client} finished" -> copy.finishedStatus
            "failed", "{client} failed", "{client} chat failed" -> copy.failedStatus
            else -> replaceClientNames(raw, copy.name)
        }
    }

    private fun clientBrand(
        selectedModel: String?,
        models: List<ChatModelOption>,
        harnessId: String?,
        localLiteRtAvailable: Boolean
    ): ClientBrand {
        val modelId = selectedModelId(selectedModel, localLiteRtAvailable)
            .ifBlank { models.firstOrNull()?.id.orEmpty() }
        if (modelId == AgentModelOptions.LOCAL_LITERT_MODEL_ID && localLiteRtAvailable) {
            return ClientBrand.Local
        }

        val selected = models.firstOrNull { it.id == modelId }
        val resolvedHarness = selected?.let(::modelHarnessId)
            ?: harnessFromModelId(modelId)
            ?: harnessId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: "openclaw"

        return when (resolvedHarness) {
            "hermes" -> ClientBrand.Hermes
            "codex" -> ClientBrand.Codex
            "local" -> ClientBrand.Local
            else -> ClientBrand.OpenClaw
        }
    }

    fun modelHarnessLabel(model: ChatModelOption): String {
        return model.harnessLabel?.takeIf { it.isNotBlank() }
            ?: when (model.harnessId?.lowercase()) {
                "openclaw" -> "OpenClaw"
                "hermes" -> "Hermes"
                "codex" -> "Codex"
                "local" -> "Local"
                else -> model.provider?.takeIf { it.isNotBlank() } ?: "OpenClaw"
            }
    }

    private fun harnessFromModelId(modelId: String): String? {
        val prefix = modelId.substringBefore(":", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.lowercase()
        return when (prefix) {
            "hermes", "codex", "local" -> prefix
            else -> null
        }
    }

    fun modelHarnessId(model: ChatModelOption): String {
        return model.harnessId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: when (model.provider?.lowercase()) {
                "hermes" -> "hermes"
                "codex" -> "codex"
                "android" -> "local"
                else -> "openclaw"
            }
    }

    fun modelHarnessSortOrder(harnessId: String): Int {
        return when (harnessId.lowercase()) {
            "openclaw" -> 0
            "hermes" -> 1
            "codex" -> 2
            "local" -> 3
            else -> 4
        }
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

    private fun statusTemplate(status: String): String {
        return replaceClientNames(
            status.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.'),
            "{client}"
        )
    }

    private fun replaceClientNames(text: String, replacement: String): String {
        return Regex("\\b(local phone model|local model|litert-lm|litert-llm|openclaw|hermes|codex|litert)\\b", RegexOption.IGNORE_CASE)
            .replace(text, replacement)
    }
}
