package dev.androidagent.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ChatTimelineKind {
    MESSAGE,
    TOOL,
    REASONING
}

data class ChatTimelineItem(
    val id: String,
    val kind: ChatTimelineKind,
    val role: String? = null,
    val text: String = "",
    val attachments: List<ChatAttachmentPreview> = emptyList(),
    val timestamp: Long? = null,
    val runId: String? = null,
    val isStreaming: Boolean = false,
    val isClearing: Boolean = false,
    val toolEvent: ChatToolEvent? = null
)

data class ChatToolEvent(
    val eventId: String,
    val runId: String?,
    val toolName: String,
    val title: String,
    val status: String,
    val summary: String?,
    val args: String?,
    val output: String?,
    val error: String?,
    val isExpanded: Boolean = false
)

data class ChatSessionRow(
    val key: String,
    val sessionId: String?,
    val label: String?,
    val displayName: String?,
    val harnessId: String?,
    val harnessLabel: String?,
    val workspacePath: String?,
    val workspaceName: String?,
    val threadPath: String?,
    val preview: String?,
    val source: String?,
    val updatedAt: Long?,
    val model: String?,
    val modelProvider: String?,
    val contextTokens: Long?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val estimatedCostUsd: Double?,
    val fastMode: Boolean?,
    val hasActiveRun: Boolean?,
    val thinkingLevel: String?,
    val reasoningLevel: String?,
    val verboseLevel: String?
)

data class ChatModelOption(
    val id: String,
    val label: String,
    val provider: String?,
    val harnessId: String?,
    val harnessLabel: String?,
    val modelId: String?,
    val contextWindow: Long?,
    val available: Boolean?,
    val reasoningOptions: List<ChatReasoningOption>?,
    val defaultReasoningEffort: String?
)
data class ChatReasoningOption(val id: String, val label: String)
enum class ChatModelSource {
    HOST,
    LOCAL
}
data class ChatCommandOption(
    val name: String,
    val description: String?,
    val category: String?,
    val aliases: List<String>,
    val acceptsArgs: Boolean,
    val source: String? = null
) {
    val isSkill: Boolean
        get() = source.equals("skill", ignoreCase = true)
}
data class ChatToolSummary(val id: String, val label: String?, val description: String?, val source: String?, val group: String?)

data class ChatUsageSummary(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val contextTokens: Long? = null,
    val estimatedCostUsd: Double? = null
) {
    val contextRatio: Float?
        get() {
            val total = totalTokens ?: return null
            val context = contextTokens ?: return null
            if (context <= 0L) return null
            return (total.toFloat() / context.toFloat()).coerceIn(0f, 1f)
        }
}

data class ChatState(
    val sessionKey: String? = null,
    val sessionId: String? = null,
    val activeRunId: String? = null,
    val isRunning: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val selectedModel: String? = null,
    val harnessId: String? = null,
    val harnessLabel: String? = null,
    val reasoningEffort: String? = "medium",
    val reasoningStreamEnabled: Boolean? = null,
    val fastMode: Boolean? = null,
    val verboseLevel: String? = null,
    val timeline: List<ChatTimelineItem> = emptyList(),
    val sessions: List<ChatSessionRow> = emptyList(),
    val models: List<ChatModelOption> = emptyList(),
    val modelSource: ChatModelSource? = null,
    val hostModels: List<ChatModelOption> = emptyList(),
    val localModels: List<ChatModelOption> = emptyList(),
    val reasoningOptions: List<ChatReasoningOption> = defaultReasoningOptions,
    val commands: List<ChatCommandOption> = emptyList(),
    val tools: List<ChatToolSummary> = emptyList(),
    val usage: ChatUsageSummary = ChatUsageSummary(),
    val unreadReplies: Map<String, ChatUnreadReply> = emptyMap()
) {
    val latestAssistantText: String?
        get() = timeline.lastOrNull { it.kind == ChatTimelineKind.MESSAGE && it.role == "assistant" }?.text

    val totalUnreadReplies: Int
        get() = unreadReplies.values.sumOf { it.count }

    fun unreadCountForSession(sessionKey: String?): Int {
        if (sessionKey.isNullOrBlank()) return 0
        return unreadReplies[sessionKey]?.count ?: 0
    }

    companion object {
        val defaultReasoningOptions = listOf(
            ChatReasoningOption("low", "low"),
            ChatReasoningOption("medium", "medium"),
            ChatReasoningOption("high", "high"),
            ChatReasoningOption("xhigh", "xhigh")
        )

        val allowedReasoningIds = setOf("none", "minimal", "low", "medium", "high", "xhigh")
    }
}

object ChatStateReducer {
    private const val SYSTEM_MESSAGE_DEDUPE_WINDOW_MS = 10_000L

    fun localUserMessage(
        state: ChatState,
        text: String,
        attachments: List<StoredChatAttachment> = emptyList()
    ): ChatState {
        val trimmed = text.trim()
        if (trimmed.isBlank() && attachments.isEmpty()) return state
        return state.copy(
            timeline = state.timeline + ChatTimelineItem(
                id = "local_${UUID.randomUUID()}",
                kind = ChatTimelineKind.MESSAGE,
                role = "user",
                text = trimmed,
                attachments = attachments.map { it.preview() },
                timestamp = System.currentTimeMillis()
            ),
            status = "Sent",
            error = null
        )
    }

    fun localSystemMessage(state: ChatState, text: String): ChatState {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return state
        return state.copy(
            timeline = mergeTimelineItem(state.timeline, ChatTimelineItem(
                id = "system_${UUID.randomUUID()}",
                kind = ChatTimelineKind.MESSAGE,
                role = "system",
                text = trimmed,
                timestamp = System.currentTimeMillis()
            )),
            error = null
        )
    }

    fun localControlNotice(command: String, args: JSONObject): String? {
        return when (normalizeControlCommand(command)) {
            "fast" -> {
                if (args.has("enabled")) {
                    "Fast mode ${if (args.optBoolean("enabled")) "enabled" else "disabled"}"
                } else {
                    null
                }
            }
            "verbose" -> args.optString("level").takeIf { it.isNotBlank() }?.let { "Verbose mode set to $it" }
            "reasoning" -> args.optString("level").takeIf { it.isNotBlank() }?.let { level ->
                "Reasoning Stream ${if (level == "stream") "enabled" else "disabled"}"
            }
            else -> null
        }
    }

    fun localControlCommand(state: ChatState, command: String, args: JSONObject): ChatState {
        val notice = localControlNotice(command, args) ?: return state
        val next = localSystemMessage(state, notice).copy(status = notice)
        return when (normalizeControlCommand(command)) {
            "fast" -> {
                if (args.has("enabled")) {
                    next.copy(fastMode = args.optBoolean("enabled"))
                } else {
                    next
                }
            }
            "verbose" -> {
                val level = args.optString("level").takeIf { it.isNotBlank() }
                if (level != null) next.copy(verboseLevel = level) else next
            }
            "reasoning" -> {
                val level = args.optString("level").takeIf { it.isNotBlank() }
                if (level != null) next.copy(reasoningStreamEnabled = level == "stream") else next
            }
            else -> next
        }
    }

    fun toggleTool(state: ChatState, eventId: String): ChatState {
        return state.copy(
            timeline = state.timeline.map { item ->
                val tool = item.toolEvent
                if (item.kind == ChatTimelineKind.TOOL && tool?.eventId == eventId) {
                    item.copy(toolEvent = tool.copy(isExpanded = !tool.isExpanded))
                } else {
                    item
                }
            }
        )
    }

    private fun normalizeControlCommand(command: String): String {
        return command.trim().removePrefix("/").substringBefore(' ').lowercase()
    }

    fun markSessionRead(state: ChatState, sessionKey: String?): ChatState {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return state
        if (!state.unreadReplies.containsKey(key)) return state
        return state.copy(unreadReplies = state.unreadReplies - key)
    }

    fun reduce(state: ChatState, message: JSONObject): ChatState {
        return when (message.optString("type")) {
            "chat.state" -> reduceState(state, message)
            "chat.history" -> reduceHistory(state, message)
            "chat.message" -> reduceMessage(state, message)
            "chat.reasoning_delta" -> reduceReasoningDelta(state, message)
            "chat.reasoning_clear" -> reduceReasoningClear(state, message)
            "chat.delta" -> reduceDelta(state, message)
            "chat.final" -> reduceFinal(state, message)
            "chat.error" -> reduceError(state, message)
            "chat.reply_available" -> reduceReplyAvailable(state, message)
            "chat.tool_event" -> reduceToolEvent(state, message)
            "chat.models" -> reduceModels(state, message)
            "chat.commands" -> state.copy(commands = parseCommands(message.optJSONArray("commands")), error = null)
            "chat.tools" -> state.copy(
                sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
                tools = parseTools(message.optJSONArray("tools")),
                error = null
            )
            "chat.sessions" -> reduceSessions(state, message)
            "chat.usage" -> state.copy(
                sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
                usage = usageWithModelContext(state, parseUsage(message.optJSONObject("usage")), state.selectedModel),
                error = null
            )
            else -> state
        }
    }

    private fun reduceState(state: ChatState, message: JSONObject): ChatState {
        val sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey
        val sessionChanged = !sessionKey.isNullOrBlank() && sessionKey != state.sessionKey
        val harnessId = normalizeHarnessId(message.optNullableString("harnessId"))
            ?: harnessFromSessionKey(sessionKey)
            ?: state.harnessId
        return state.copy(
            sessionKey = sessionKey,
            sessionId = message.optNullableString("sessionId") ?: state.sessionId,
            harnessId = harnessId,
            harnessLabel = message.optNullableString("harnessLabel") ?: state.harnessLabel,
            activeRunId = message.optNullableString("runId"),
            isRunning = message.optBoolean("isRunning", state.isRunning),
            status = message.optNullableString("status") ?: state.status,
            selectedModel = selectedModelForActiveHarness(
                current = state.selectedModel,
                incoming = message.optNullableString("model"),
                activeHarnessId = harnessId
            ),
            reasoningEffort = normalizeReasoningEffort(message.optNullableString("reasoningEffort"), state.reasoningEffort),
            reasoningStreamEnabled = message.optNullableBoolean("reasoningStream") ?: state.reasoningStreamEnabled,
            fastMode = message.optNullableBoolean("fastMode") ?: state.fastMode,
            verboseLevel = message.optNullableString("verboseLevel") ?: state.verboseLevel,
            timeline = if (sessionChanged) emptyList() else state.timeline,
            usage = if (sessionChanged) ChatUsageSummary() else state.usage,
            error = null
        )
    }

    private fun reduceHistory(state: ChatState, message: JSONObject): ChatState {
        val incomingSessionKey = message.optNullableString("sessionKey") ?: state.sessionKey
        val history = parseHistory(message.optJSONArray("messages"))
        val localOverlayMessages = if (incomingSessionKey != null && incomingSessionKey == state.sessionKey) {
            state.timeline.filter { item ->
                shouldKeepLocalMessageAcrossHistory(item, history)
            }
        } else {
            emptyList()
        }
        val historyIds = history.map { it.id }.toSet()
        return state.copy(
            sessionKey = incomingSessionKey,
            sessionId = message.optNullableString("sessionId") ?: state.sessionId,
            timeline = mergeHistoryWithLocalStatus(
                current = state.timeline,
                history = history,
                localStatus = localOverlayMessages.filterNot { it.id in historyIds }
            ),
            error = null
        )
    }

    private fun reduceMessage(state: ChatState, message: JSONObject): ChatState {
        val item = parseHistoryMessage(message.optJSONObject("message"), "message") ?: return state
        return state.copy(
            sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
            sessionId = message.optNullableString("sessionId") ?: state.sessionId,
            timeline = mergeTimelineItem(state.timeline, item),
            error = null
        )
    }

    private fun reduceReasoningDelta(state: ChatState, message: JSONObject): ChatState {
        val runId = message.optNullableString("runId") ?: state.activeRunId ?: "active"
        val sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey
        val delta = message.optString("delta")
        if (delta.isBlank()) return state
        val replace = message.optBoolean("replace", false)
        val itemId = "reasoning_$runId"
        val existing = state.timeline.firstOrNull { it.id == itemId }
        val nextText = if (replace || existing?.isClearing == true) delta else (existing?.text.orEmpty() + delta)
        return state.copy(
            sessionKey = sessionKey,
            activeRunId = runId,
            isRunning = true,
            status = "Thinking",
            reasoningStreamEnabled = true,
            timeline = upsertTimeline(state.timeline, ChatTimelineItem(
                id = itemId,
                kind = ChatTimelineKind.REASONING,
                text = nextText,
                runId = runId,
                isStreaming = true,
                isClearing = false
            )),
            error = null
        )
    }

    private fun reduceReasoningClear(state: ChatState, message: JSONObject): ChatState {
        val runId = message.optNullableString("runId")
        return state.copy(
            sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
            timeline = markReasoningClearing(state.timeline, runId)
        )
    }

    private fun reduceDelta(state: ChatState, message: JSONObject): ChatState {
        val runId = message.optNullableString("runId") ?: state.activeRunId ?: "active"
        val sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey
        val delta = message.optString("delta")
        val replace = message.optBoolean("replace", false)
        val itemId = "assistant_$runId"
        val existing = state.timeline.firstOrNull { it.id == itemId }
        val nextText = if (replace) delta else (existing?.text.orEmpty() + delta)
        val timeline = markReasoningClearing(state.timeline, runId)
        return state.copy(
            sessionKey = sessionKey,
            activeRunId = runId,
            isRunning = true,
            status = "Thinking",
            timeline = upsertTimeline(timeline, ChatTimelineItem(
                id = itemId,
                kind = ChatTimelineKind.MESSAGE,
                role = "assistant",
                text = nextText,
                runId = runId,
                isStreaming = true
            )),
            error = null
        )
    }

    private fun reduceFinal(state: ChatState, message: JSONObject): ChatState {
        val runId = message.optNullableString("runId") ?: state.activeRunId ?: "final"
        val itemId = "assistant_$runId"
        val text = message.optString("text")
        val timeline = markReasoningClearing(state.timeline, runId)
        return state.copy(
            sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
            activeRunId = null,
            isRunning = false,
            status = "Finished",
            timeline = upsertTimeline(timeline, ChatTimelineItem(
                id = itemId,
                kind = ChatTimelineKind.MESSAGE,
                role = "assistant",
                text = text,
                runId = runId,
                isStreaming = false
            )),
            error = null
        )
    }

    private fun reduceError(state: ChatState, message: JSONObject): ChatState {
        val text = message.optString("message", "OpenAgent chat failed")
        val runId = message.optNullableString("runId") ?: state.activeRunId
        return state.copy(
            sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
            activeRunId = null,
            isRunning = false,
            status = "Failed",
            error = text,
            timeline = markReasoningClearing(state.timeline, runId) + ChatTimelineItem(
                id = "error_${UUID.randomUUID()}",
                kind = ChatTimelineKind.MESSAGE,
                role = "system",
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun reduceReplyAvailable(state: ChatState, message: JSONObject): ChatState {
        val sessionKey = message.optNullableString("sessionKey") ?: return state
        val runId = message.optNullableString("runId") ?: return state
        val existing = state.unreadReplies[sessionKey]
        if (existing?.runIds?.contains(runId) == true) {
            return state
        }
        val harnessId = normalizeHarnessId(message.optNullableString("harnessId"))
            ?: harnessFromSessionKey(sessionKey)
            ?: existing?.source?.harnessId
        val source = ChatReplySource(
            sessionId = message.optNullableString("sessionId") ?: existing?.source?.sessionId,
            sessionLabel = message.optNullableString("sessionLabel") ?: existing?.source?.sessionLabel,
            sessionDisplayName = message.optNullableString("sessionDisplayName") ?: existing?.source?.sessionDisplayName,
            harnessId = harnessId,
            harnessLabel = message.optNullableString("harnessLabel")
                ?: existing?.source?.harnessLabel
                ?: harnessId?.let(ChatModelCatalog::harnessLabel),
            model = message.optNullableString("model") ?: existing?.source?.model
        )
        val updated = ChatUnreadReply(
            count = (existing?.count ?: 0) + 1,
            runIds = (existing?.runIds ?: emptySet()) + runId,
            latestRunId = runId,
            latestPreview = message.optNullableString("textPreview") ?: existing?.latestPreview,
            latestStatus = message.optNullableString("status") ?: existing?.latestStatus,
            source = source,
            receivedAt = message.optNullableLong("receivedAt") ?: System.currentTimeMillis()
        )
        return state.copy(
            unreadReplies = state.unreadReplies + (sessionKey to updated),
            error = null
        )
    }

    private fun reduceToolEvent(state: ChatState, message: JSONObject): ChatState {
        val event = ChatToolEvent(
            eventId = message.optString("eventId", "tool_${UUID.randomUUID()}"),
            runId = message.optNullableString("runId"),
            toolName = message.optString("toolName", "tool"),
            title = message.optString("title", message.optString("toolName", "Tool activity")),
            status = message.optString("status", "running"),
            summary = message.optNullableString("summary"),
            args = compactJson(message.opt("args")),
            output = compactJson(message.opt("output")),
            error = message.optNullableString("error")
        )
        val existing = state.timeline.firstOrNull { it.id == "tool_${event.eventId}" }?.toolEvent
        val merged = event.copy(
            title = event.title.ifBlank { existing?.title.orEmpty() }.ifBlank { event.toolName },
            summary = event.summary ?: existing?.summary,
            args = event.args ?: existing?.args,
            output = event.output ?: existing?.output,
            error = event.error ?: existing?.error,
            isExpanded = existing?.isExpanded ?: false
        )
        return state.copy(
            sessionKey = message.optNullableString("sessionKey") ?: state.sessionKey,
            timeline = upsertTimeline(state.timeline, ChatTimelineItem(
                id = "tool_${event.eventId}",
                kind = ChatTimelineKind.TOOL,
                runId = event.runId,
                toolEvent = merged
            )),
            error = null
        )
    }

    private fun reduceModels(state: ChatState, message: JSONObject): ChatState {
        val models = parseModels(message.optJSONArray("models"))
        val source = parseModelSource(message)
        val selectedModel = state.selectedModel
        val modelReasoning = models.firstOrNull { it.id == selectedModel }?.reasoningOptions
            ?.filter { it.id in ChatState.allowedReasoningIds }
        val incoming = modelReasoning ?: parseReasoning(message.optJSONArray("reasoningOptions"))
        val filtered = incoming.filter { it.id in ChatState.allowedReasoningIds }
        val reasoning = (if (filtered.isNotEmpty()) filtered else state.reasoningOptions)
            .ifEmpty { ChatState.defaultReasoningOptions }
        val selectedReasoning = models.firstOrNull { it.id == selectedModel }?.defaultReasoningEffort
            ?.takeIf { option -> reasoning.any { it.id == option } }
        val nextState = state.copy(
            models = models,
            modelSource = source,
            hostModels = if (source == ChatModelSource.HOST) models else state.hostModels,
            localModels = if (source == ChatModelSource.LOCAL) models else state.localModels,
            reasoningEffort = selectedReasoning ?: state.reasoningEffort?.takeIf { current -> reasoning.any { it.id == current } } ?: reasoning.firstOrNull()?.id,
            reasoningOptions = reasoning,
            commands = if (source == ChatModelSource.LOCAL) emptyList() else state.commands,
            error = null
        )
        return nextState.copy(usage = usageWithModelContext(nextState, nextState.usage, nextState.selectedModel))
    }

    private fun parseModelSource(message: JSONObject): ChatModelSource? {
        return when (message.optNullableString("source")?.trim()?.lowercase()) {
            "host" -> ChatModelSource.HOST
            "local" -> ChatModelSource.LOCAL
            else -> null
        }
    }

    private fun reduceSessions(state: ChatState, message: JSONObject): ChatState {
        val sessions = parseSessions(message.optJSONArray("sessions"))
        val selectedKey = message.optNullableString("selectedSessionKey") ?: state.sessionKey
        val sessionChanged = !selectedKey.isNullOrBlank() && selectedKey != state.sessionKey
        val selected = sessions.firstOrNull { it.key == selectedKey }
        val selectedHarnessId = normalizeHarnessId(selected?.harnessId)
            ?: harnessFromSessionKey(selectedKey)
            ?: state.harnessId
        val selectedModel = selectedModelForActiveHarness(
            current = state.selectedModel,
            incoming = selected?.model,
            activeHarnessId = selectedHarnessId
        )
        val usage = selected?.let {
            ChatUsageSummary(
                inputTokens = it.inputTokens,
                outputTokens = it.outputTokens,
                totalTokens = it.totalTokens,
                contextTokens = it.contextTokens,
                estimatedCostUsd = it.estimatedCostUsd
            )
        }?.let { usageWithModelContext(state, it, selectedModel) } ?: state.usage
        return state.copy(
            sessionKey = selectedKey,
            sessions = sessions,
            selectedModel = selectedModel,
            harnessId = selectedHarnessId,
            harnessLabel = selected?.harnessLabel ?: state.harnessLabel,
            reasoningEffort = normalizeReasoningEffort(selected?.thinkingLevel, state.reasoningEffort),
            reasoningStreamEnabled = selected?.reasoningLevel?.let(::reasoningStreamEnabled) ?: state.reasoningStreamEnabled,
            fastMode = selected?.fastMode ?: state.fastMode,
            verboseLevel = selected?.verboseLevel ?: state.verboseLevel,
            timeline = if (sessionChanged) emptyList() else state.timeline,
            usage = if (sessionChanged && selected == null) ChatUsageSummary() else usage,
            unreadReplies = mergeUnreadSessionMetadata(state.unreadReplies, sessions),
            error = null
        )
    }

    private fun selectedModelForActiveHarness(
        current: String?,
        incoming: String?,
        activeHarnessId: String?
    ): String? = ChatModelCatalog.selectedModelForActiveHarness(current, incoming, activeHarnessId)

    private fun normalizeModelForHarness(model: String?, harnessId: String?): String? =
        ChatModelCatalog.normalizeModelForHarness(model, harnessId)

    private fun harnessForModel(model: String): String = ChatModelCatalog.harnessForModel(model)

    private fun harnessFromSessionKey(sessionKey: String?): String? = ChatModelCatalog.harnessFromSessionKey(sessionKey)

    private fun normalizeHarnessId(harnessId: String?): String? = ChatModelCatalog.normalizeHarnessId(harnessId)

    private fun usageWithModelContext(
        state: ChatState,
        usage: ChatUsageSummary,
        selectedModel: String?
    ): ChatUsageSummary {
        if (usage.contextTokens != null || usage.totalTokens == null) return usage
        val contextWindow = ChatModelCatalog.contextWindowForModel(state, selectedModel) ?: return usage
        if (contextWindow <= 0L) return usage
        return usage.copy(contextTokens = contextWindow)
    }

    private fun mergeUnreadSessionMetadata(
        unreadReplies: Map<String, ChatUnreadReply>,
        sessions: List<ChatSessionRow>
    ): Map<String, ChatUnreadReply> {
        if (unreadReplies.isEmpty() || sessions.isEmpty()) return unreadReplies
        val sessionsByKey = sessions.associateBy { it.key }
        return unreadReplies.mapValues { (sessionKey, unread) ->
            val session = sessionsByKey[sessionKey] ?: return@mapValues unread
            val harnessId = normalizeHarnessId(session.harnessId)
                ?: session.model?.takeIf { it.isNotBlank() }?.let(::harnessForModel)
                ?: unread.source.harnessId
            unread.copy(
                source = unread.source.copy(
                    sessionId = session.sessionId ?: unread.source.sessionId,
                    sessionLabel = session.label ?: unread.source.sessionLabel,
                    sessionDisplayName = session.displayName ?: unread.source.sessionDisplayName,
                    harnessId = harnessId,
                    harnessLabel = session.harnessLabel
                        ?: unread.source.harnessLabel
                        ?: harnessId?.let(ChatModelCatalog::harnessLabel),
                    model = session.model ?: unread.source.model
                )
            )
        }
    }

    private fun mergeTimelineItem(timeline: List<ChatTimelineItem>, item: ChatTimelineItem): List<ChatTimelineItem> {
        val duplicateSystemIndex = if (item.role == "system" && item.text.isNotBlank()) {
            timeline.indexOfFirst { existing ->
                existing.role == "system" &&
                    existing.text == item.text &&
                    existing.id != item.id &&
                    existing.timestamp != null &&
                    item.timestamp != null &&
                    kotlin.math.abs(existing.timestamp - item.timestamp) <= SYSTEM_MESSAGE_DEDUPE_WINDOW_MS
            }
        } else {
            -1
        }
        if (duplicateSystemIndex >= 0) {
            return timeline.toMutableList().also { it[duplicateSystemIndex] = item }
        }
        return upsertTimeline(timeline, item)
    }

    private fun upsertTimeline(timeline: List<ChatTimelineItem>, item: ChatTimelineItem): List<ChatTimelineItem> {
        val index = timeline.indexOfFirst { it.id == item.id }
        if (index == -1) return timeline + item
        return timeline.toMutableList().also { it[index] = item }
    }

    private fun mergeHistoryWithLocalStatus(
        current: List<ChatTimelineItem>,
        history: List<ChatTimelineItem>,
        localStatus: List<ChatTimelineItem>
    ): List<ChatTimelineItem> {
        if (localStatus.isEmpty()) return history
        val merged = history + localStatus
        if (merged.any { it.timestamp == null }) return merged
        val existingIndexById = current.mapIndexedNotNull { index, item ->
            item.id.takeIf { it.isNotBlank() }?.let { it to index }
        }.toMap()
        return merged
            .mapIndexed { fallbackIndex, item ->
                val timestamp = item.timestamp
                val existingIndex = existingIndexById[item.id]
                TimelineOrderingItem(
                    item = item,
                    timestamp = timestamp,
                    existingIndex = existingIndex,
                    fallbackIndex = fallbackIndex
                )
            }
            .sortedWith(
                compareBy<TimelineOrderingItem> { it.timestamp ?: Long.MAX_VALUE }
                    .thenBy { it.existingIndex ?: Int.MAX_VALUE }
                    .thenBy { it.fallbackIndex }
            )
            .map { it.item }
    }

    private fun shouldKeepLocalMessageAcrossHistory(
        item: ChatTimelineItem,
        history: List<ChatTimelineItem>
    ): Boolean {
        if (item.kind != ChatTimelineKind.MESSAGE) return false
        if (item.id.startsWith("system_")) return true
        if (!item.id.startsWith("local_") || item.role != "user") return false
        if (item.text.trimStart().startsWith("/")) return true
        return history.none { historyItem -> sameUserMessage(historyItem, item) }
    }

    private fun sameUserMessage(a: ChatTimelineItem, b: ChatTimelineItem): Boolean {
        return a.kind == ChatTimelineKind.MESSAGE &&
            b.kind == ChatTimelineKind.MESSAGE &&
            a.role == "user" &&
            b.role == "user" &&
            a.text == b.text &&
            a.attachments == b.attachments
    }

    private fun markReasoningClearing(timeline: List<ChatTimelineItem>, runId: String?): List<ChatTimelineItem> {
        return timeline.map { item ->
            if (item.kind == ChatTimelineKind.REASONING && !item.isClearing && (runId == null || item.runId == runId)) {
                item.copy(isStreaming = false, isClearing = true)
            } else {
                item
            }
        }
    }

    private fun parseHistory(array: JSONArray?): List<ChatTimelineItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                parseHistoryMessage(array.optJSONObject(index), "history_$index")?.let { add(it) }
            }
        }
    }

    private fun parseHistoryMessage(item: JSONObject?, fallbackId: String): ChatTimelineItem? {
        if (item == null) return null
        val text = item.optString("text")
        val attachments = ChatAttachmentPreviewJson.fromJsonArray(item.optJSONArray("attachments"))
        if (text.isBlank() && attachments.isEmpty()) return null
        return ChatTimelineItem(
            id = item.optNullableString("id") ?: fallbackId,
            kind = ChatTimelineKind.MESSAGE,
            role = item.optString("role", "assistant"),
            text = text,
            attachments = attachments,
            timestamp = item.optNullableLong("timestamp")
        )
    }

    private fun parseSessions(array: JSONArray?): List<ChatSessionRow> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = item.optNullableString("key") ?: continue
                add(ChatSessionRow(
                    key = key,
                    sessionId = item.optNullableString("sessionId"),
                    label = item.optNullableString("label"),
                    displayName = item.optNullableString("displayName"),
                    harnessId = item.optNullableString("harnessId"),
                    harnessLabel = item.optNullableString("harnessLabel"),
                    workspacePath = item.optNullableString("workspacePath"),
                    workspaceName = item.optNullableString("workspaceName"),
                    threadPath = item.optNullableString("threadPath"),
                    preview = item.optNullableString("preview"),
                    source = item.optNullableString("source"),
                    updatedAt = item.optNullableLong("updatedAt"),
                    model = item.optNullableString("model"),
                    modelProvider = item.optNullableString("modelProvider"),
                    contextTokens = item.optNullableLong("contextTokens"),
                    inputTokens = item.optNullableLong("inputTokens"),
                    outputTokens = item.optNullableLong("outputTokens"),
                    totalTokens = item.optNullableLong("totalTokens"),
                    estimatedCostUsd = item.optNullableDouble("estimatedCostUsd"),
                    fastMode = item.optNullableBoolean("fastMode"),
                    hasActiveRun = item.optNullableBoolean("hasActiveRun"),
                    thinkingLevel = item.optNullableString("thinkingLevel"),
                    reasoningLevel = item.optNullableString("reasoningLevel"),
                    verboseLevel = item.optNullableString("verboseLevel")
                ))
            }
        }
    }

    private fun parseModels(array: JSONArray?): List<ChatModelOption> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optNullableString("id") ?: continue
                add(ChatModelOption(
                    id = id,
                    label = item.optString("label", id),
                    provider = item.optNullableString("provider"),
                    harnessId = item.optNullableString("harnessId"),
                    harnessLabel = item.optNullableString("harnessLabel"),
                    modelId = item.optNullableString("modelId"),
                    contextWindow = item.optNullableLong("contextWindow"),
                    available = item.optNullableBoolean("available"),
                    reasoningOptions = parseReasoning(item.optJSONArray("reasoningOptions")).takeIf { it.isNotEmpty() },
                    defaultReasoningEffort = item.optNullableString("defaultReasoningEffort")
                ))
            }
        }
    }

    private fun parseReasoning(array: JSONArray?): List<ChatReasoningOption> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optNullableString("id") ?: continue
                add(ChatReasoningOption(id = id, label = item.optString("label", id)))
            }
        }
    }

    private fun parseCommands(array: JSONArray?): List<ChatCommandOption> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optNullableString("name") ?: continue
                add(ChatCommandOption(
                    name = name,
                    description = item.optNullableString("description"),
                    category = item.optNullableString("category"),
                    aliases = item.optJSONArray("textAliases").toStringList(),
                    acceptsArgs = item.optBoolean("acceptsArgs", false),
                    source = item.optNullableString("source")
                ))
            }
        }
    }

    private fun parseTools(array: JSONArray?): List<ChatToolSummary> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optNullableString("id") ?: continue
                add(ChatToolSummary(
                    id = id,
                    label = item.optNullableString("label"),
                    description = item.optNullableString("description"),
                    source = item.optNullableString("source"),
                    group = item.optNullableString("group")
                ))
            }
        }
    }

    private fun parseUsage(value: JSONObject?): ChatUsageSummary {
        if (value == null) return ChatUsageSummary()
        return ChatUsageSummary(
            inputTokens = value.optNullableLong("inputTokens"),
            outputTokens = value.optNullableLong("outputTokens"),
            totalTokens = value.optNullableLong("totalTokens"),
            contextTokens = value.optNullableLong("contextTokens"),
            estimatedCostUsd = value.optNullableDouble("estimatedCostUsd")
        )
    }

    private fun compactJson(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name)
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name)
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private fun reasoningStreamEnabled(level: String): Boolean {
        return level == "stream"
    }

    private fun normalizeReasoningEffort(incoming: String?, current: String?): String {
        val normalizedIncoming = incoming?.trim()?.lowercase()
        val normalizedCurrent = current?.trim()?.lowercase()
        return when {
            normalizedIncoming != null && normalizedIncoming in ChatState.allowedReasoningIds -> normalizedIncoming
            normalizedCurrent != null && normalizedCurrent in ChatState.allowedReasoningIds -> normalizedCurrent
            else -> "medium"
        }
    }

    private data class TimelineOrderingItem(
        val item: ChatTimelineItem,
        val timestamp: Long?,
        val existingIndex: Int?,
        val fallbackIndex: Int
    )
}
