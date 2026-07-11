package dev.androidagent.chat

import dev.androidagent.AgentModelOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateReducerTest {
    @Test
    fun historyReplacesTimelineWithMessageRows() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "u1").put("role", "user").put("text", "Hello"))
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Hi there"))))

        assertEquals("agent:main:main", state.sessionKey)
        assertEquals(2, state.timeline.size)
        assertEquals("user", state.timeline[0].role)
        assertEquals("Hi there", state.timeline[1].text)
    }

    @Test
    fun historyAcceptsAttachmentOnlyMessages() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject()
                    .put("id", "u1")
                    .put("role", "user")
                    .put("text", "")
                    .put("attachments", JSONArray()
                        .put(JSONObject()
                            .put("id", "att_1")
                            .put("kind", "image")
                            .put("displayName", "photo.png")
                            .put("mimeType", "image/png")
                            .put("sizeBytes", 12L)
                            .put("localPath", "/tmp/photo.png"))))))

        assertEquals(1, state.timeline.size)
        assertEquals("", state.timeline[0].text)
        assertEquals("photo.png", state.timeline[0].attachments.single().displayName)
        assertTrue(state.timeline[0].attachments.single().isImage)
    }

    @Test
    fun messageAppendsAndUpsertsWithoutReplacingTimeline() {
        val withHistory = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Hi there"))))
        val withMessage = ChatStateReducer.reduce(withHistory, JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", "agent:main:main")
            .put("message", JSONObject()
                .put("id", "u1")
                .put("role", "user")
                .put("text", "Open settings")
                .put("timestamp", 123L)))
        val upserted = ChatStateReducer.reduce(withMessage, JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", "agent:main:main")
            .put("message", JSONObject()
                .put("id", "u1")
                .put("role", "user")
                .put("text", "Open Bluetooth settings")))

        assertEquals(2, withMessage.timeline.size)
        assertEquals("assistant", withMessage.timeline[0].role)
        assertEquals("user", withMessage.timeline[1].role)
        assertEquals("Open settings", withMessage.timeline[1].text)
        assertEquals(2, upserted.timeline.size)
        assertEquals("Open Bluetooth settings", upserted.timeline[1].text)
    }

    @Test
    fun historyKeepsLocalSystemCommandConfirmations() {
        val withNotice = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", "agent:main:main")
            .put("message", JSONObject()
                .put("id", "system_run1")
                .put("role", "system")
                .put("text", "Reasoning Stream enabled")
                .put("timestamp", 123L)))
        val refreshed = ChatStateReducer.reduce(withNotice, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Done"))))

        assertEquals(2, refreshed.timeline.size)
        assertEquals("assistant", refreshed.timeline[0].role)
        assertEquals("system", refreshed.timeline[1].role)
        assertEquals("Reasoning Stream enabled", refreshed.timeline[1].text)
    }

    @Test
    fun historyDoesNotCarryLocalSlashCommandsAcrossSessions() {
        val withSlash = ChatStateReducer.localUserMessage(
            ChatState(sessionKey = "codex:first"),
            "/compact"
        )
        val switched = ChatStateReducer.reduce(withSlash, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "codex:second")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Older thread"))))

        assertEquals("codex:second", switched.sessionKey)
        assertEquals(listOf("Older thread"), switched.timeline.map { it.text })
    }

    @Test
    fun historyKeepsPendingLocalUserMessagesUntilServerHistoryCatchesUp() {
        val withLocal = ChatStateReducer.localUserMessage(
            ChatState(sessionKey = "agent:main:main"),
            "hi"
        )
        val staleHistory = ChatStateReducer.reduce(withLocal, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()))
        val caughtUpHistory = ChatStateReducer.reduce(staleHistory, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "u1").put("role", "user").put("text", "hi"))
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Hello"))))

        assertEquals(listOf("hi"), staleHistory.timeline.map { it.text })
        assertEquals(listOf("u1", "a1"), caughtUpHistory.timeline.map { it.id })
        assertEquals(listOf("hi", "Hello"), caughtUpHistory.timeline.map { it.text })
    }

    @Test
    fun stateSessionChangeClearsStaleTimeline() {
        val stale = ChatState(
            sessionKey = "agent:main:first",
            timeline = listOf(ChatTimelineItem(
                id = "a1",
                kind = ChatTimelineKind.MESSAGE,
                role = "assistant",
                text = "Previous thread"
            )),
            usage = ChatUsageSummary(totalTokens = 100, contextTokens = 1_000)
        )

        val switched = ChatStateReducer.reduce(stale, JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "agent:main:second"))

        assertEquals("agent:main:second", switched.sessionKey)
        assertEquals(emptyList<ChatTimelineItem>(), switched.timeline)
        assertEquals(ChatUsageSummary(), switched.usage)
    }

    @Test
    fun sessionsSessionChangeClearsStaleTimeline() {
        val stale = ChatState(
            sessionKey = "agent:main:first",
            timeline = listOf(ChatTimelineItem(
                id = "a1",
                kind = ChatTimelineKind.MESSAGE,
                role = "assistant",
                text = "Previous thread"
            ))
        )

        val switched = ChatStateReducer.reduce(stale, JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:second")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:second")
                .put("displayName", "Second"))))

        assertEquals("agent:main:second", switched.sessionKey)
        assertEquals(emptyList<ChatTimelineItem>(), switched.timeline)
    }

    @Test
    fun historyMergesLocalStatusMessagesChronologicallyWhenTimestampsExist() {
        val withNotice = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.message")
            .put("sessionKey", "agent:main:main")
            .put("message", JSONObject()
                .put("id", "system_run1")
                .put("role", "system")
                .put("text", "Fast mode disabled")
                .put("timestamp", 200L)))
        val refreshed = ChatStateReducer.reduce(withNotice, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "u1").put("role", "user").put("text", "First").put("timestamp", 100L))
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Done").put("timestamp", 150L))
                .put(JSONObject().put("id", "u2").put("role", "user").put("text", "Second").put("timestamp", 300L))
                .put(JSONObject().put("id", "a2").put("role", "assistant").put("text", "Done again").put("timestamp", 350L))))

        assertEquals(listOf("u1", "a1", "system_run1", "u2", "a2"), refreshed.timeline.map { it.id })
    }

    @Test
    fun localControlCommandUpdatesFastModeStateAndNotice() {
        val enabled = ChatStateReducer.localControlCommand(ChatState(fastMode = false), "fast", JSONObject()
            .put("enabled", true))
        val disabled = ChatStateReducer.localControlCommand(enabled, "/fast", JSONObject()
            .put("enabled", false))

        assertEquals(true, enabled.fastMode)
        assertEquals("Fast mode enabled", enabled.status)
        assertEquals("Fast mode enabled", enabled.timeline.single().text)
        assertEquals(false, disabled.fastMode)
        assertEquals("Fast mode disabled", disabled.status)
        assertEquals("Fast mode disabled", disabled.timeline.last().text)
    }

    @Test
    fun localControlCommandUpdatesOtherMenuToggles() {
        val verbose = ChatStateReducer.localControlCommand(ChatState(), "verbose", JSONObject()
            .put("level", "high"))
        val reasoningOff = ChatStateReducer.localControlCommand(verbose, "reasoning", JSONObject()
            .put("level", "off"))

        assertEquals("high", verbose.verboseLevel)
        assertEquals("Verbose mode set to high", verbose.status)
        assertEquals(false, reasoningOff.reasoningStreamEnabled)
        assertEquals("Reasoning Stream disabled", reasoningOff.status)
    }

    @Test
    fun commandSnapshotsPreserveSkillSource() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.commands")
            .put("commands", JSONArray()
                .put(JSONObject()
                    .put("name", "status")
                    .put("description", "Show status")
                    .put("textAliases", JSONArray().put("/status")))
                .put(JSONObject()
                    .put("name", "commit-message")
                    .put("description", "Draft a commit message")
                    .put("source", "skill"))))

        assertEquals(listOf("status", "commit-message"), state.commands.map { it.name })
        assertEquals(null, state.commands[0].source)
        assertEquals(false, state.commands[0].isSkill)
        assertEquals("skill", state.commands[1].source)
        assertEquals(true, state.commands[1].isSkill)
    }

    @Test
    fun localModelSnapshotClearsHostCommands() {
        val withCommands = ChatState(
            commands = listOf(ChatCommandOption(
                name = "status",
                description = "Show status",
                category = null,
                aliases = emptyList(),
                acceptsArgs = false
            ))
        )

        val localRefresh = ChatStateReducer.reduce(withCommands, JSONObject()
            .put("type", "chat.models")
            .put("source", "local")
            .put("models", JSONArray()
                .put(JSONObject()
                    .put("id", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                    .put("label", "Local LiteRT-LM")
                    .put("provider", "android")
                    .put("available", true))))

        assertEquals(emptyList<ChatCommandOption>(), localRefresh.commands)
    }

    @Test
    fun modelSnapshotsStayScopedToExplicitSource() {
        val hostModels = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.models")
            .put("source", "host")
            .put("models", JSONArray()
                .put(model("gpt-5.5", "OpenClaw", "openclaw", "openclaw"))
                .put(model("hermes:gpt-5.5", "Hermes", "hermes", "hermes"))
                .put(model("codex:gpt-5.3-codex", "Codex", "codex", "codex"))))
        val localRefresh = ChatStateReducer.reduce(hostModels, JSONObject()
            .put("type", "chat.models")
            .put("source", "local")
            .put("models", JSONArray()
                .put(JSONObject()
                    .put("id", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                    .put("label", "Local LiteRT-LM")
                    .put("provider", "android")
                    .put("available", true))))

        assertEquals(
            listOf(AgentModelOptions.LOCAL_LITERT_MODEL_ID),
            localRefresh.models.map { it.id }
        )
        assertEquals(ChatModelSource.LOCAL, localRefresh.modelSource)
        assertEquals(
            listOf("gpt-5.5", "hermes:gpt-5.5", "codex:gpt-5.3-codex"),
            localRefresh.hostModels.map { it.id }
        )
        assertEquals(listOf(AgentModelOptions.LOCAL_LITERT_MODEL_ID), localRefresh.localModels.map { it.id })
    }

    @Test
    fun deltasAppendThenFinalStopsRun() {
        val withDelta = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Hel"))
        val withMoreDelta = ChatStateReducer.reduce(withDelta, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "lo"))
        val final = ChatStateReducer.reduce(withMoreDelta, JSONObject()
            .put("type", "chat.final")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("text", "Hello"))

        assertTrue(withMoreDelta.isRunning)
        assertEquals("Hello", withMoreDelta.timeline.single().text)
        assertFalse(final.isRunning)
        assertEquals("Hello", final.timeline.single().text)
        assertFalse(final.timeline.single().isStreaming)
    }

    @Test
    fun replaceDeltaOverwritesStreamingAssistantText() {
        val withDelta = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Old"))
        val replaced = ChatStateReducer.reduce(withDelta, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "New")
            .put("replace", true))
        val appended = ChatStateReducer.reduce(replaced, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", " text"))

        assertEquals("New", replaced.timeline.single().text)
        assertTrue(replaced.timeline.single().isStreaming)
        assertEquals("New text", appended.timeline.single().text)
    }

    @Test
    fun toolEventsUpsertAndKeepExpansionState() {
        val first = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "agent:main:main")
            .put("eventId", "tool1")
            .put("toolName", "exec")
            .put("title", "Running tests")
            .put("status", "running")
            .put("args", JSONObject().put("command", "npm test")))
        val expanded = ChatStateReducer.toggleTool(first, "tool1")
        val completed = ChatStateReducer.reduce(expanded, JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "agent:main:main")
            .put("eventId", "tool1")
            .put("toolName", "exec")
            .put("title", "Tests passed")
            .put("status", "completed")
            .put("output", "ok"))

        assertEquals(1, completed.timeline.size)
        val tool = completed.timeline.single().toolEvent!!
        assertEquals("completed", tool.status)
        assertEquals("{\"command\":\"npm test\"}", tool.args)
        assertEquals("ok", tool.output)
        assertTrue(tool.isExpanded)
    }

    @Test
    fun blockedPermissionSurvivesHistoryRefreshAndKeepsRunActive() {
        val blocked = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "devin:session")
            .put("runId", "run1")
            .put("eventId", "permission1")
            .put("toolName", "webfetch")
            .put("title", "Allow web fetch")
            .put("status", "blocked")
            .put("actions", JSONArray().put(JSONObject()
                .put("id", "allow_once")
                .put("label", "Allow once")
                .put("command", "devin.permission")
                .put("args", JSONObject()
                    .put("permissionId", "permission1")
                    .put("optionId", "allow_once")))))

        assertTrue(blocked.isRunning)
        assertEquals("run1", blocked.activeRunId)

        val refreshed = ChatStateReducer.reduce(blocked, JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "devin:session")
            .put("messages", JSONArray().put(JSONObject()
                .put("id", "user1")
                .put("role", "user")
                .put("text", "Research this"))))

        assertEquals(2, refreshed.timeline.size)
        val permission = refreshed.timeline.single { it.kind == ChatTimelineKind.TOOL }.toolEvent
        assertEquals("blocked", permission?.status)
        assertEquals("Allow once", permission?.actions?.single()?.label)
    }

    @Test
    fun errorForFollowUpRequestDoesNotClearDifferentActiveRun() {
        val running = ChatState(
            sessionKey = "devin:session",
            activeRunId = "active-run",
            isRunning = true,
            status = "Waiting for permission"
        )
        val withBusyError = ChatStateReducer.reduce(running, JSONObject()
            .put("type", "chat.error")
            .put("sessionKey", "devin:session")
            .put("runId", "follow-up-request")
            .put("message", "A Devin task is already running in this session."))

        assertTrue(withBusyError.isRunning)
        assertEquals("active-run", withBusyError.activeRunId)
        assertEquals("Waiting for permission", withBusyError.status)
    }

    @Test
    fun reasoningDeltasStreamIntoTemporaryRowThenAssistantClearsIt() {
        val withReasoning = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.reasoning_delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Checking"))
        val withMoreReasoning = ChatStateReducer.reduce(withReasoning, JSONObject()
            .put("type", "chat.reasoning_delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", " files"))
        val withAssistant = ChatStateReducer.reduce(withMoreReasoning, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Done"))

        val reasoning = withMoreReasoning.timeline.single()
        assertEquals(ChatTimelineKind.REASONING, reasoning.kind)
        assertEquals("Checking files", reasoning.text)
        assertTrue(reasoning.isStreaming)
        assertEquals(true, withMoreReasoning.reasoningStreamEnabled)

        val clearingReasoning = withAssistant.timeline.first { it.kind == ChatTimelineKind.REASONING }
        assertTrue(clearingReasoning.isClearing)
        assertFalse(clearingReasoning.isStreaming)
        assertEquals("Done", withAssistant.timeline.first { it.role == "assistant" }.text)
    }

    @Test
    fun stateAndSessionsTrackReasoningStream() {
        val fromState = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "agent:main:main")
            .put("reasoningStream", true))
        val fromSessions = ChatStateReducer.reduce(fromState, JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("reasoningLevel", "off"))))

        assertEquals(true, fromState.reasoningStreamEnabled)
        assertEquals(false, fromSessions.reasoningStreamEnabled)
    }

    @Test
    fun chatStateTracksHarnessMetadata() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "hermes:session")
            .put("harnessId", "hermes")
            .put("harnessLabel", "Hermes"))

        assertEquals("hermes", state.harnessId)
        assertEquals("Hermes", state.harnessLabel)
    }

    @Test
    fun stateSwitchesSelectedModelWhenRestoredHarnessChanges() {
        val state = ChatStateReducer.reduce(ChatState(
            selectedModel = "gpt-5.5",
            harnessId = "openclaw"
        ), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "codex:pixel")
            .put("harnessId", "codex")
            .put("harnessLabel", "Codex")
            .put("model", "codex:gpt-5.3-codex"))

        assertEquals("codex:gpt-5.3-codex", state.selectedModel)
        assertEquals("codex", state.harnessId)
        assertEquals("Codex", state.harnessLabel)
    }

    @Test
    fun invalidReasoningEffortFallsBackToLastKnownOrMedium() {
        val initial = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "agent:main:main")
            .put("reasoningEffort", "off"))
        val withKnown = ChatStateReducer.reduce(initial.copy(reasoningEffort = "high"), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("thinkingLevel", "off"))))

        assertEquals("medium", initial.reasoningEffort)
        assertEquals("high", withKnown.reasoningEffort)
    }

    @Test
    fun modelsDoNotSelectByListOrder() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.models")
            .put("models", JSONArray()
                .put(JSONObject()
                    .put("id", "openai-codex/gpt-5.4")
                    .put("label", "gpt-5.4")
                    .put("available", false))
                .put(JSONObject()
                    .put("id", "openai-codex/gpt-5.5")
                    .put("label", "gpt-5.5")
                    .put("available", true))))

        assertEquals(null, state.selectedModel)
    }

    @Test
    fun modelsDoNotOverrideExistingModelSelection() {
        val state = ChatStateReducer.reduce(ChatState(selectedModel = "gpt-5.4"), JSONObject()
            .put("type", "chat.models")
            .put("models", JSONArray().put(JSONObject()
                .put("id", "openai-codex/gpt-5.5")
                .put("label", "gpt-5.5")
                .put("available", true))))

        assertEquals("gpt-5.4", state.selectedModel)
    }

    @Test
    fun sessionsDoNotClobberPersistedModelSelection() {
        val withModels = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.models")
            .put("models", JSONArray().put(JSONObject()
                .put("id", "openai-codex/gpt-5.5")
                .put("label", "gpt-5.5")
                .put("available", true))))
        val withSessions = ChatStateReducer.reduce(withModels.copy(selectedModel = "openai-codex/gpt-5.5"), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("model", "gpt-5.4"))))

        assertEquals("openai-codex/gpt-5.5", withSessions.selectedModel)
    }

    @Test
    fun sessionsSwitchSelectedModelWhenRestoredHarnessChanges() {
        val state = ChatStateReducer.reduce(ChatState(
            selectedModel = "gpt-5.5",
            harnessId = "openclaw"
        ), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "codex:pixel")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "codex:pixel")
                .put("harnessId", "codex")
                .put("harnessLabel", "Codex")
                .put("model", "codex:gpt-5.3-codex"))))

        assertEquals("codex:gpt-5.3-codex", state.selectedModel)
        assertEquals("codex", state.harnessId)
        assertEquals("Codex", state.harnessLabel)
    }

    @Test
    fun sessionsUpdateUsageAndSelections() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("model", "gpt-5.5")
                .put("thinkingLevel", "high")
                .put("totalTokens", 50)
                .put("contextTokens", 100))))

        assertEquals("gpt-5.5", state.selectedModel)
        assertEquals("high", state.reasoningEffort)
        assertEquals(50L, state.usage.totalTokens)
        assertEquals(0.5f, state.usage.contextRatio)
    }

    @Test
    fun usageFallsBackToSelectedModelContextWindow() {
        val withModels = ChatStateReducer.reduce(ChatState(selectedModel = "codex:gpt-5.3-codex"), JSONObject()
            .put("type", "chat.models")
            .put("models", JSONArray().put(JSONObject()
                .put("id", "codex:gpt-5.3-codex")
                .put("label", "Codex")
                .put("contextWindow", 400_000))))
        val withUsage = ChatStateReducer.reduce(withModels, JSONObject()
            .put("type", "chat.usage")
            .put("sessionKey", "codex:main")
            .put("usage", JSONObject()
                .put("totalTokens", 32_000)))

        assertEquals(32_000L, withUsage.usage.totalTokens)
        assertEquals(400_000L, withUsage.usage.contextTokens)
        assertEquals(0.08f, withUsage.usage.contextRatio)
    }

    @Test
    fun localUsagePayloadProvidesContextRatio() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "local:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "local:main")
                .put("model", AgentModelOptions.LOCAL_LITERT_MODEL_ID)
                .put("inputTokens", 64)
                .put("outputTokens", 32)
                .put("totalTokens", 96)
                .put("contextTokens", 4096))))

        assertEquals(AgentModelOptions.LOCAL_LITERT_MODEL_ID, state.selectedModel)
        assertEquals(96L, state.usage.totalTokens)
        assertEquals(4096L, state.usage.contextTokens)
        assertEquals(96f / 4096f, state.usage.contextRatio)
    }

    @Test
    fun replyAvailableAddsUnreadPerSessionAndDedupesRunIds() {
        val first = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "agent:main:first")
            .put("runId", "run1")
            .put("status", "completed")
            .put("textPreview", "First reply"))
        val duplicate = ChatStateReducer.reduce(first, JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "agent:main:first")
            .put("runId", "run1")
            .put("status", "completed")
            .put("textPreview", "First reply again"))
        val secondSession = ChatStateReducer.reduce(duplicate, JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "agent:main:second")
            .put("runId", "run2")
            .put("status", "failed")
            .put("textPreview", "Second reply"))

        assertEquals(1, first.unreadCountForSession("agent:main:first"))
        assertEquals(1, duplicate.unreadCountForSession("agent:main:first"))
        assertEquals(2, secondSession.totalUnreadReplies)
        assertEquals("Second reply", secondSession.unreadReplies["agent:main:second"]?.latestPreview)
    }

    @Test
    fun replyAvailableTracksHarnessMetadataAndLatestUnreadSession() {
        val hermes = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "hermes:nightly")
            .put("runId", "run1")
            .put("status", "completed")
            .put("harnessId", "hermes")
            .put("harnessLabel", "Hermes")
            .put("model", "hermes:gpt-5.5")
            .put("receivedAt", 100L))
        val codex = ChatStateReducer.reduce(hermes, JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "codex:workspace")
            .put("runId", "run2")
            .put("status", "completed")
            .put("harnessId", "codex")
            .put("harnessLabel", "Codex")
            .put("model", "codex:gpt-5.3-codex")
            .put("receivedAt", 200L))

        val unread = codex.unreadReplies["hermes:nightly"]
        assertEquals("hermes", unread?.source?.harnessId)
        assertEquals("Hermes", unread?.source?.harnessLabel)
        assertEquals("hermes:gpt-5.5", unread?.source?.model)
        assertEquals(1, codex.unreadCountForHarness("hermes"))
        assertEquals(1, codex.unreadCountForHarness("codex"))
        assertEquals("codex:workspace", codex.latestUnreadSessionKey())
    }

    @Test
    fun markSessionReadClearsOnlyThatSession() {
        val withUnread = listOf("first" to "run1", "second" to "run2").fold(ChatState()) { state, (session, run) ->
            ChatStateReducer.reduce(state, JSONObject()
                .put("type", "chat.reply_available")
                .put("sessionKey", "agent:main:$session")
                .put("runId", run)
                .put("textPreview", session))
        }

        val cleared = ChatStateReducer.markSessionRead(withUnread, "agent:main:first")

        assertEquals(0, cleared.unreadCountForSession("agent:main:first"))
        assertEquals(1, cleared.unreadCountForSession("agent:main:second"))
        assertEquals(1, cleared.totalUnreadReplies)
    }

    @Test
    fun sessionsRefreshEnrichesUnreadSessionLabels() {
        val withUnread = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.reply_available")
            .put("sessionKey", "agent:main:first")
            .put("runId", "run1"))

        val withSessions = ChatStateReducer.reduce(withUnread, JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:other")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:first")
                .put("sessionId", "session-1")
                .put("displayName", "Project notes")
                .put("harnessId", "hermes")
                .put("harnessLabel", "Hermes")
                .put("model", "hermes:gpt-5.5"))))

        val unread = withSessions.unreadReplies["agent:main:first"]
        assertEquals("Project notes", unread?.source?.sessionDisplayName)
        assertEquals("Project notes", unread?.displayNameFor("agent:main:first"))
        assertEquals("hermes", unread?.source?.harnessId)
        assertEquals("Hermes", unread?.source?.harnessLabel)
        assertEquals("hermes:gpt-5.5", unread?.source?.model)
    }

    @Test
    fun devinStateAndSessionInferHarnessFromPrefix() {
        val state = ChatStateReducer.reduce(ChatState(
            selectedModel = "gpt-5.5",
            harnessId = "openclaw"
        ), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "devin:workspace")
            .put("harnessId", "devin")
            .put("harnessLabel", "Devin")
            .put("model", "devin:anthropic/claude-3-7-sonnet-20250219"))

        assertEquals("devin:anthropic/claude-3-7-sonnet-20250219", state.selectedModel)
        assertEquals("devin", state.harnessId)
        assertEquals("Devin", state.harnessLabel)

        val sessions = ChatStateReducer.reduce(state, JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "devin:workspace")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "devin:workspace")
                .put("harnessId", "devin")
                .put("harnessLabel", "Devin")
                .put("model", "devin:anthropic/claude-3-7-sonnet-20250219")
                .put("workspacePath", "~/DevinWorkspace"))))

        assertEquals("devin:workspace", sessions.sessionKey)
        assertEquals("~/DevinWorkspace", sessions.sessions.single().workspacePath)
    }

    @Test
    fun toolEventActionsPreserveOpaquePermissionArgs() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "devin:session")
            .put("eventId", "perm1")
            .put("toolName", "permission")
            .put("title", "Permission request")
            .put("actions", JSONArray().put(JSONObject()
                .put("id", "allow")
                .put("label", "Allow")
                .put("command", "permission_reply")
                .put("args", JSONObject()
                    .put("permissionId", "devin:sandbox:write")
                    .put("optionId", "allow")))))

        val action = state.timeline.single().toolEvent?.actions?.single()
        assertEquals("permission_reply", action?.command)
        assertEquals("devin:sandbox:write", action?.args?.optString("permissionId"))
        assertEquals("allow", action?.args?.optString("optionId"))
    }

    @Test
    fun toolEventActionsPreserveEveryExactAcpPermissionOptionId() {
        val firstOptionId = "allow_once:workspace/write-v2"
        val secondOptionId = "reject_always:policy#locked"
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "devin:session")
            .put("eventId", "permission-opaque")
            .put("toolName", "permission")
            .put("title", "Permission request")
            .put("actions", JSONArray()
                .put(JSONObject()
                    .put("id", "first")
                    .put("label", "Allow this write")
                    .put("command", "permission_reply")
                    .put("args", JSONObject()
                        .put("permissionId", "permission:run/session")
                        .put("optionId", firstOptionId)))
                .put(JSONObject()
                    .put("id", "second")
                    .put("label", "Never allow")
                    .put("command", "permission_reply")
                    .put("args", JSONObject()
                        .put("permissionId", "permission:run/session")
                        .put("optionId", secondOptionId)))))

        val actions = state.timeline.single().toolEvent?.actions.orEmpty()
        assertEquals(listOf("first", "second"), actions.map { it.id })
        assertEquals(listOf(firstOptionId, secondOptionId), actions.map { it.args.getString("optionId") })
        assertEquals(listOf("permission_reply", "permission_reply"), actions.map { it.command })
    }

    private fun model(id: String, label: String, provider: String, harnessId: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("provider", provider)
            .put("harnessId", harnessId)
            .put("harnessLabel", label)
            .put("available", true)
    }
}
