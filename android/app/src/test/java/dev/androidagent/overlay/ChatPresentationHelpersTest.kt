package dev.androidagent.overlay

import dev.androidagent.AgentModelOptions
import dev.androidagent.R
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatModelSource
import dev.androidagent.chat.ChatReplySource
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatUnreadReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationHelpersTest {
    @Test
    fun hidesAgentInternalToolsByIdOrLabelPrefix() {
        assertTrue(ChatPresentationHelpers.isAgentInternalTool("exec", null))
        assertTrue(ChatPresentationHelpers.isAgentInternalTool("custom", "Web Search Result"))
        assertTrue(ChatPresentationHelpers.isAgentInternalTool("session_status_detail", "status"))
        assertFalse(ChatPresentationHelpers.isAgentInternalTool("phone_tap", "Phone tap"))
    }

    @Test
    fun mergeModelOptionsPreservesLocalDefaultsAndRemoteOverrides() {
        val merged = ChatPresentationHelpers.mergeModelOptions(
            gatewayModels = listOf(ChatModelOption(
                id = "gpt-5.5",
                label = "GPT 5.5 Gateway",
                provider = "gateway",
                harnessId = "openclaw",
                harnessLabel = "OpenClaw",
                modelId = "gpt-5.5",
                contextWindow = 200_000,
                available = false,
                reasoningOptions = null,
                defaultReasoningEffort = null
            )),
            localLiteRtAvailable = true
        )

        assertEquals("GPT 5.5 Gateway", merged.first { it.id == "gpt-5.5" }.label)
        assertEquals(false, merged.first { it.id == "gpt-5.5" }.available)
        assertEquals("Local LiteRT-LM", merged.first { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }.label)
    }

    @Test
    fun mergeModelOptionsHidesUnavailableLocalLiteRt() {
        val merged = ChatPresentationHelpers.mergeModelOptions(
            gatewayModels = listOf(ChatModelOption(
                id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                label = "Remote local",
                provider = "gateway",
                harnessId = "openclaw",
                harnessLabel = "OpenClaw",
                modelId = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                contextWindow = null,
                available = true,
                reasoningOptions = null,
                defaultReasoningEffort = null
            )),
            localLiteRtAvailable = false
        )

        assertFalse(merged.any { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID })
    }

    @Test
    fun labelsModelsAndReasoningForCompactControls() {
        val models = listOf(ChatModelOption(
            id = "openai/gpt-5.5",
            label = "gpt-5.5",
            provider = "openai",
            harnessId = "openclaw",
            harnessLabel = "OpenClaw",
            modelId = "openai/gpt-5.5",
            contextWindow = null,
            available = true,
            reasoningOptions = null,
            defaultReasoningEffort = null
        ))

        assertEquals("5.5", ChatPresentationHelpers.formatModelLabel("openai/gpt-5.5", models, localLiteRtAvailable = true))
        assertEquals("5.5", ChatPresentationHelpers.formatModelLabel(AgentModelOptions.LOCAL_LITERT_MODEL_ID, emptyList(), localLiteRtAvailable = false))
        assertEquals("Med", ChatPresentationHelpers.formatReasoningLabel("medium"))
        assertEquals("Xhigh", ChatPresentationHelpers.formatReasoningLabel("xhigh"))
        assertEquals("Reason", ChatPresentationHelpers.formatReasoningLabel(""))
    }

    @Test
    fun duplicateModelLabelsAcrossHarnessesRemainSeparatePickerIds() {
        val merged = ChatPresentationHelpers.mergeModelOptions(
            gatewayModels = listOf(
                ChatModelOption(
                    id = "gpt-5.5",
                    label = "GPT 5.5",
                    provider = "openai-codex",
                    harnessId = "openclaw",
                    harnessLabel = "OpenClaw",
                    modelId = "gpt-5.5",
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                ),
                ChatModelOption(
                    id = "hermes:gpt-5.5",
                    label = "GPT 5.5",
                    provider = "hermes",
                    harnessId = "hermes",
                    harnessLabel = "Hermes",
                    modelId = "gpt-5.5",
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                ),
                ChatModelOption(
                    id = "opencode:openai/gpt-5.5",
                    label = "GPT 5.5",
                    provider = "opencode",
                    harnessId = "opencode",
                    harnessLabel = "OpenCode",
                    modelId = "openai/gpt-5.5",
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                )
            ),
            localLiteRtAvailable = false
        )

        assertEquals(listOf("gpt-5.5", "hermes:gpt-5.5", "opencode:openai/gpt-5.5"), merged.map { it.id }.filter { it.contains("gpt-5.5") })
        assertEquals("OpenClaw", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == "gpt-5.5" }))
        assertEquals("Hermes", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == "hermes:gpt-5.5" }))
        assertEquals("OpenCode", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == "opencode:openai/gpt-5.5" }))
        assertEquals("openclaw", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == "gpt-5.5" }))
        assertEquals("hermes", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == "hermes:gpt-5.5" }))
        assertEquals(listOf("openclaw", "hermes", "codex", "opencode", "local"), listOf("local", "opencode", "codex", "hermes", "openclaw").sortedBy(ChatPresentationHelpers::modelHarnessSortOrder))
    }

    @Test
    fun modelPickerOptionsHideDisabledHarnesses() {
        val state = ChatState(
            models = listOf(
                model("gpt-5.5", harnessId = "openclaw"),
                model("hermes:gpt-5.5", harnessId = "hermes"),
                model("codex:gpt-5.3-codex", harnessId = "codex"),
                model("opencode:openai/gpt-5.5", harnessId = "opencode")
            ),
            hostModels = listOf(
                model("gpt-5.5", harnessId = "openclaw"),
                model("hermes:gpt-5.5", harnessId = "hermes"),
                model("codex:gpt-5.3-codex", harnessId = "codex"),
                model("opencode:openai/gpt-5.5", harnessId = "opencode")
            ),
            localModels = listOf(model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android"))
        )

        val merged = ChatPresentationHelpers.modelPickerOptions(
            state = state,
            localLiteRtAvailable = true,
            enabledHarnessIds = setOf("hermes", "local")
        )

        assertEquals(listOf("hermes:gpt-5.5", AgentModelOptions.LOCAL_LITERT_MODEL_ID), merged.map { it.id })
        assertEquals("hermes:gpt-5.5", ChatPresentationHelpers.selectedModelId("gpt-5.5", localLiteRtAvailable = true, models = merged))
        assertEquals("hermes:gpt-5.5", ChatPresentationHelpers.formatModelLabel("gpt-5.5", merged, localLiteRtAvailable = true))
        assertEquals("LiteRT-LLM", ChatPresentationHelpers.modelProviderSublabel(merged.last(), "Local"))
    }

    @Test
    fun modelPickerOptionsCombineExplicitHostAndLocalSnapshots() {
        val state = ChatState(
            models = listOf(model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android")),
            modelSource = ChatModelSource.LOCAL,
            hostModels = listOf(
                model("gpt-5.5", harnessId = "openclaw"),
                model("hermes:gpt-5.5", harnessId = "hermes"),
                model("codex:gpt-5.3-codex", harnessId = "codex")
            ),
            localModels = listOf(model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android"))
        )

        val merged = ChatPresentationHelpers.modelPickerOptions(state, localLiteRtAvailable = true)

        assertEquals(
            listOf("gpt-5.5", "hermes:gpt-5.5", "codex:gpt-5.3-codex", AgentModelOptions.LOCAL_LITERT_MODEL_ID),
            merged.map { it.id }.filter {
                it == "gpt-5.5" ||
                    it == "hermes:gpt-5.5" ||
                    it == "codex:gpt-5.3-codex" ||
                    it == AgentModelOptions.LOCAL_LITERT_MODEL_ID
            }
        )
        assertEquals("local", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }))
    }

    @Test
    fun harnessModelGroupsFilterDisabledHarnessesAndIncludeLocal() {
        val groups = ChatPresentationHelpers.harnessModelGroups(
            models = listOf(
                model("gpt-5.5", harnessId = "openclaw"),
                model("hermes:qwen", harnessId = "hermes"),
                model("codex:gpt-5.3-codex", harnessId = "codex"),
                model("opencode:openai/gpt-5.5", harnessId = "opencode"),
                model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android")
            ),
            enabledHarnessIds = setOf("hermes", "local")
        )

        assertEquals(listOf("hermes", "local"), groups.map { it.id })
        assertEquals(listOf("Hermes", "Local"), groups.map { it.label })
        assertEquals(listOf("hermes:qwen"), groups.first().models.map { it.id })
    }

    @Test
    fun defaultModelForHarnessUsesConfiguredModelOrFirstAvailable() {
        val models = listOf(
            model("hermes:qwen", harnessId = "hermes"),
            model("hermes:gpt-5.5", harnessId = "hermes"),
            model("codex:gpt-5.3-codex", harnessId = "codex"),
            model("opencode:openai/gpt-5.5", harnessId = "opencode")
        )

        assertEquals(
            "hermes:gpt-5.5",
            ChatPresentationHelpers.defaultModelForHarness(
                harnessId = "hermes",
                configuredDefaultModel = "gpt-5.5",
                models = models,
                enabledHarnessIds = setOf("hermes", "codex")
            )
        )
        assertEquals(
            "hermes:qwen",
            ChatPresentationHelpers.defaultModelForHarness(
                harnessId = "hermes",
                configuredDefaultModel = "missing",
                models = models,
                enabledHarnessIds = setOf("hermes", "codex")
            )
        )
        assertEquals(
            null,
            ChatPresentationHelpers.defaultModelForHarness(
                harnessId = "codex",
                configuredDefaultModel = "codex:gpt-5.3-codex",
                models = models,
                enabledHarnessIds = setOf("hermes")
            )
        )
        assertEquals(
            "opencode:openai/gpt-5.5",
            ChatPresentationHelpers.defaultModelForHarness(
                harnessId = "opencode",
                configuredDefaultModel = "openai/gpt-5.5",
                models = models,
                enabledHarnessIds = setOf("hermes", "codex", "opencode")
            )
        )
    }

    @Test
    fun modelPickerOptionsDoesNotFakeHostModelsWhenLocalSnapshotArrivesFirst() {
        val state = ChatState(
            models = listOf(ChatModelOption(
                id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                label = "Local LiteRT-LM",
                provider = "android",
                harnessId = null,
                harnessLabel = null,
                modelId = null,
                contextWindow = 4096,
                available = true,
                reasoningOptions = null,
                defaultReasoningEffort = null
            )),
            modelSource = ChatModelSource.LOCAL,
            localModels = listOf(ChatModelOption(
                id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                label = "Local LiteRT-LM",
                provider = "android",
                harnessId = null,
                harnessLabel = null,
                modelId = null,
                contextWindow = 4096,
                available = true,
                reasoningOptions = null,
                defaultReasoningEffort = null
            ))
        )

        val merged = ChatPresentationHelpers.modelPickerOptions(state, localLiteRtAvailable = true)

        assertFalse(merged.any { it.id.startsWith("hermes:") })
        assertFalse(merged.any { it.id.startsWith("codex:") })
        assertEquals("Local", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }))
        assertEquals("local", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }))
    }

    @Test
    fun resolvesClientBrandFromSelectedModelHarness() {
        val models = listOf(
            model("hermes:gpt-5.5", harnessId = "hermes"),
            model("codex:gpt-5.3-codex", harnessId = "codex"),
            model("opencode:openai/gpt-5.5", harnessId = "opencode"),
            model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android")
        )

        val hermes = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "hermes:gpt-5.5",
            models = models,
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )
        val codex = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "codex:gpt-5.3-codex",
            models = models,
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )
        val opencode = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "opencode:openai/gpt-5.5",
            models = models,
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )
        val local = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
            models = models,
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )

        assertEquals(ClientBrand.Hermes, hermes.brand)
        assertEquals("Hermes", hermes.title)
        assertEquals(R.drawable.hermes_nous_logo, hermes.logoRes)
        assertEquals(BrandTitleTreatment.PLAIN, hermes.titleTreatment)
        assertEquals(ClientBrand.Codex, codex.brand)
        assertEquals("Codex", codex.title)
        assertEquals(R.drawable.codex_bubble_logo, codex.logoRes)
        assertEquals(BrandTitleTreatment.PLAIN, codex.titleTreatment)
        assertEquals(ClientBrand.OpenCode, opencode.brand)
        assertEquals("OpenCode", opencode.title)
        assertEquals(R.drawable.opencode_logo_plate, opencode.logoRes)
        assertEquals(BrandTitleTreatment.PLAIN, opencode.titleTreatment)
        assertEquals(ClientBrand.Local, local.brand)
        assertEquals("LiteRT-LLM", local.title)
        assertEquals(R.drawable.huggingface_logo, local.logoRes)
    }

    @Test
    fun resolvesClientBrandFromModelPrefixAndHarnessFallback() {
        val prefixed = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "hermes:qwen",
            models = emptyList(),
            harnessId = "openclaw",
            localLiteRtAvailable = false
        )
        val fallback = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = null,
            models = emptyList(),
            harnessId = "opencode",
            localLiteRtAvailable = false
        )
        val default = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "gpt-5.5",
            models = emptyList(),
            harnessId = null,
            localLiteRtAvailable = false
        )

        assertEquals(ClientBrand.Hermes, prefixed.brand)
        assertEquals(ClientBrand.OpenCode, fallback.brand)
        assertEquals(ClientBrand.OpenClaw, default.brand)
        assertEquals("OpenClaw", default.title)
        assertEquals(R.drawable.openclaw_bubble_logo, default.logoRes)
        assertEquals(BrandTitleTreatment.OPENCLAW_ACCENT, default.titleTreatment)
    }

    @Test
    fun chatStatusCopyUsesSelectedClientBrand() {
        val hermes = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "hermes:qwen",
            models = emptyList(),
            harnessId = "openclaw",
            localLiteRtAvailable = false
        )
        val codex = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "codex:gpt-5.3-codex",
            models = emptyList(),
            harnessId = "openclaw",
            localLiteRtAvailable = false
        )
        val opencode = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "opencode:openai/gpt-5.5",
            models = emptyList(),
            harnessId = "openclaw",
            localLiteRtAvailable = false
        )

        assertEquals("Message Hermes", hermes.copy.composerPlaceholder)
        assertEquals("Loading Hermes Chat", ChatPresentationHelpers.chatStatusText("Loading OpenClaw chat", isRunning = false, hermes))
        assertEquals("Hermes is thinking", ChatPresentationHelpers.chatStatusText("OpenClaw is responding", isRunning = true, hermes))
        assertEquals("Hermes finished", ChatPresentationHelpers.chatStatusText("Codex finished", isRunning = false, hermes))
        assertEquals("Message Codex", codex.copy.composerPlaceholder)
        assertEquals("Stop Codex turn", codex.copy.stopTurnDescription)
        assertEquals("Message OpenCode", opencode.copy.composerPlaceholder)
        assertEquals("Stop OpenCode turn", opencode.copy.stopTurnDescription)
    }

    @Test
    fun localChatCopyUsesLiteRtBrand() {
        val local = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
            models = listOf(model(AgentModelOptions.LOCAL_LITERT_MODEL_ID, harnessId = "local", provider = "android")),
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )

        assertEquals("LiteRT-LLM", local.title)
        assertEquals("Message LiteRT", local.copy.composerPlaceholder)
        assertEquals("LiteRT is thinking", ChatPresentationHelpers.chatStatusText("Local model is working", isRunning = true, local))
        assertEquals("Sent to LiteRT", ChatPresentationHelpers.chatStatusText("Sent to OpenClaw", isRunning = false, local))
    }

    @Test
    fun verboseLevelCyclesThroughMenuStates() {
        assertEquals("off", ChatPresentationHelpers.normalizedVerboseLevel(null))
        assertEquals("on", ChatPresentationHelpers.normalizedVerboseLevel("high"))
        assertEquals("on", ChatPresentationHelpers.nextVerboseLevel("off"))
        assertEquals("full", ChatPresentationHelpers.nextVerboseLevel("on"))
        assertEquals("off", ChatPresentationHelpers.nextVerboseLevel("full"))
    }

    @Test
    fun sessionLabelUsesBestAvailableName() {
        assertEquals("Display", ChatPresentationHelpers.sessionLabel(session(displayName = "Display", label = "Label", sessionId = "session-1")))
        assertEquals("Label", ChatPresentationHelpers.sessionLabel(session(label = "Label", sessionId = "session-1")))
        assertEquals("session-1", ChatPresentationHelpers.sessionLabel(session(sessionId = "session-1")))
        assertEquals("fallback", ChatPresentationHelpers.sessionLabel(session(key = "agent:main:fallback")))
    }

    @Test
    fun sessionSourceLabelsIncludeHarnessBreadcrumbs() {
        assertEquals(
            "Hermes / Nightly cron",
            ChatPresentationHelpers.sessionSourceSublabel(session(
                key = "hermes:nightly",
                harnessId = "hermes",
                harnessLabel = "Hermes",
                preview = "Nightly cron"
            ))
        )
        assertEquals(
            "Codex / ~/Projects/app",
            ChatPresentationHelpers.sessionSourceSublabel(session(
                key = "codex:workspace",
                harnessId = "codex",
                harnessLabel = "Codex",
                workspacePath = "/Users/example/Projects/app"
            ))
        )
        assertEquals(
            "Hermes / Nightly cron",
            ChatPresentationHelpers.unreadReplySourceLabel(
                "hermes:nightly",
                ChatUnreadReply(source = ChatReplySource(
                    sessionDisplayName = "Nightly cron",
                    harnessId = "hermes",
                    harnessLabel = "Hermes"
                ))
            )
        )
    }

    @Test
    fun codexSessionPickerGroupsWorkspacesAndQuickChats() {
        val sections = CodexSessionPickerSections.build(
            sessions = listOf(
                session(
                    key = "codex:workspace",
                    harnessId = "codex",
                    harnessLabel = "Codex",
                    displayName = "Workspace chat",
                    workspacePath = "/Users/example/Projects/app",
                    updatedAt = 200,
                    model = "gpt-5.3-codex"
                ),
                session(
                    key = "codex:quick",
                    harnessId = "codex",
                    harnessLabel = "Codex",
                    displayName = "Quick chat",
                    preview = "quick question",
                    updatedAt = 100,
                    model = "gpt-5.3-codex"
                )
            ),
            selectedSessionKey = "codex:workspace",
            expandedWorkspaceKeys = setOf("/Users/example/Projects/app"),
            unreadCountForSession = { key -> if (key == "codex:quick") 2 else 0 },
            onToggleWorkspace = {},
            onSelectSession = {}
        )

        assertEquals(listOf(null, "QuickChats"), sections.map { it.title })
        assertEquals("~/Projects/app", sections[0].rows[0].label)
        assertEquals("Active workspace, 1 session", sections[0].rows[0].sublabel)
        assertTrue(sections[0].rows[0].emphasizeLabel)
        assertEquals("Codex / ~/Projects/app", sections[0].rows[1].sublabel)
        assertTrue(sections[0].rows[1].selected)
        assertEquals("Codex / quick question", sections[1].rows[0].sublabel)
        assertEquals(2, sections[1].rows[0].badgeCount)
    }

    @Test
    fun codexSessionPickerCollapsesWorkspaceRowsAndSortsByRecency() {
        val sections = CodexSessionPickerSections.build(
            sessions = listOf(
                session(
                    key = "codex:old",
                    harnessId = "codex",
                    harnessLabel = "Codex",
                    displayName = "Old workspace chat",
                    workspacePath = "/Users/example/Projects/old",
                    updatedAt = 100,
                    model = "gpt-5.3-codex"
                ),
                session(
                    key = "codex:recent",
                    harnessId = "codex",
                    harnessLabel = "Codex",
                    displayName = "Recent workspace chat",
                    workspacePath = "/Users/example/Projects/recent",
                    updatedAt = 300,
                    model = "gpt-5.3-codex"
                ),
                session(
                    key = "codex:old-newer",
                    harnessId = "codex",
                    harnessLabel = "Codex",
                    displayName = "Old newer workspace chat",
                    workspacePath = "/Users/example/Projects/old",
                    updatedAt = 200,
                    model = "gpt-5.3-codex"
                )
            ),
            selectedSessionKey = "codex:old-newer",
            expandedWorkspaceKeys = setOf("/Users/example/Projects/old"),
            unreadCountForSession = { 0 },
            onToggleWorkspace = {},
            onSelectSession = {}
        )

        assertEquals(listOf("~/Projects/recent", "~/Projects/old"), sections.map { it.rows[0].label })
        assertEquals(1, sections[0].rows.size)
        assertEquals(3, sections[1].rows.size)
        assertEquals("Old newer workspace chat", sections[1].rows[1].label)
        assertEquals("Old workspace chat", sections[1].rows[2].label)
    }

    @Test
    fun opencodeSessionPickerGroupsWorkspacesAndQuickChats() {
        val sections = CodexSessionPickerSections.build(
            sessions = listOf(
                session(
                    key = "opencode:workspace",
                    harnessId = "opencode",
                    harnessLabel = "OpenCode",
                    displayName = "Workspace chat",
                    workspacePath = "/Users/example/Projects/app",
                    updatedAt = 200,
                    model = "opencode:openai/gpt-5.5"
                ),
                session(
                    key = "opencode:quick",
                    harnessId = "opencode",
                    harnessLabel = "OpenCode",
                    displayName = "Quick chat",
                    preview = "quick question",
                    updatedAt = 100,
                    model = "opencode:openai/gpt-5.5"
                )
            ),
            selectedSessionKey = "opencode:workspace",
            expandedWorkspaceKeys = setOf("/Users/example/Projects/app"),
            unreadCountForSession = { key -> if (key == "opencode:quick") 1 else 0 },
            onToggleWorkspace = {},
            onSelectSession = {}
        )

        assertEquals(listOf(null, "QuickChats"), sections.map { it.title })
        assertEquals("~/Projects/app", sections[0].rows[0].label)
        assertEquals("Active workspace, 1 session", sections[0].rows[0].sublabel)
        assertTrue(sections[0].rows[0].emphasizeLabel)
        assertEquals("OpenCode / ~/Projects/app", sections[0].rows[1].sublabel)
        assertTrue(sections[0].rows[1].selected)
        assertEquals("OpenCode / quick question", sections[1].rows[0].sublabel)
        assertEquals(1, sections[1].rows[0].badgeCount)
    }

    private fun session(
        key: String = "agent:main:default",
        sessionId: String? = null,
        label: String? = null,
        displayName: String? = null,
        workspacePath: String? = null,
        workspaceName: String? = null,
        preview: String? = null,
        source: String? = null,
        updatedAt: Long? = null,
        model: String? = null,
        harnessId: String? = "openclaw",
        harnessLabel: String? = "OpenClaw"
    ): ChatSessionRow {
        return ChatSessionRow(
            key = key,
            sessionId = sessionId,
            label = label,
            displayName = displayName,
            harnessId = harnessId,
            harnessLabel = harnessLabel,
            workspacePath = workspacePath,
            workspaceName = workspaceName,
            threadPath = null,
            preview = preview,
            source = source,
            updatedAt = updatedAt,
            model = model,
            modelProvider = null,
            contextTokens = null,
            inputTokens = null,
            outputTokens = null,
            totalTokens = null,
            estimatedCostUsd = null,
            fastMode = null,
            hasActiveRun = null,
            thinkingLevel = null,
            reasoningLevel = null,
            verboseLevel = null
        )
    }

    private fun model(
        id: String,
        harnessId: String,
        provider: String? = harnessId
    ): ChatModelOption {
        return ChatModelOption(
            id = id,
            label = id,
            provider = provider,
            harnessId = harnessId,
            harnessLabel = harnessId.replaceFirstChar { it.uppercase() },
            modelId = id.substringAfter(":"),
            contextWindow = null,
            available = true,
            reasoningOptions = null,
            defaultReasoningEffort = null
        )
    }
}
