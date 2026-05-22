package dev.androidagent.overlay

import dev.androidagent.AgentModelOptions
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatModelSource
import dev.androidagent.chat.ChatSessionRow
import dev.androidagent.chat.ChatState
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
                )
            ),
            localLiteRtAvailable = false
        )

        assertEquals(listOf("gpt-5.5", "hermes:gpt-5.5"), merged.map { it.id }.filter { it.contains("gpt-5.5") })
        assertEquals("OpenClaw", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == "gpt-5.5" }))
        assertEquals("Hermes", ChatPresentationHelpers.modelHarnessLabel(merged.first { it.id == "hermes:gpt-5.5" }))
        assertEquals("openclaw", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == "gpt-5.5" }))
        assertEquals("hermes", ChatPresentationHelpers.modelHarnessId(merged.first { it.id == "hermes:gpt-5.5" }))
        assertEquals(listOf("openclaw", "hermes", "codex", "local"), listOf("local", "codex", "hermes", "openclaw").sortedBy(ChatPresentationHelpers::modelHarnessSortOrder))
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
    fun resolvesClientBrandFromSelectedModelHarness() {
        val models = listOf(
            model("hermes:gpt-5.5", harnessId = "hermes"),
            model("codex:gpt-5.3-codex", harnessId = "codex"),
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
        val local = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
            models = models,
            harnessId = "openclaw",
            localLiteRtAvailable = true
        )

        assertEquals(ClientBrand.Hermes, hermes.brand)
        assertEquals("Hermes", hermes.title)
        assertFalse(hermes.usesWhiteTitle)
        assertEquals(ClientBrand.Codex, codex.brand)
        assertEquals("Codex", codex.title)
        assertTrue(codex.usesWhiteTitle)
        assertEquals(ClientBrand.Local, local.brand)
        assertEquals("LiteRT-LLM", local.title)
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
            harnessId = "codex",
            localLiteRtAvailable = false
        )
        val default = ChatPresentationHelpers.clientBrandPresentation(
            selectedModel = "gpt-5.5",
            models = emptyList(),
            harnessId = null,
            localLiteRtAvailable = false
        )

        assertEquals(ClientBrand.Hermes, prefixed.brand)
        assertEquals(ClientBrand.Codex, fallback.brand)
        assertEquals(ClientBrand.OpenClaw, default.brand)
        assertEquals("OpenClaw", default.title)
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

    private fun session(
        key: String = "agent:main:default",
        sessionId: String? = null,
        label: String? = null,
        displayName: String? = null
    ): ChatSessionRow {
        return ChatSessionRow(
            key = key,
            sessionId = sessionId,
            label = label,
            displayName = displayName,
            harnessId = "openclaw",
            harnessLabel = "OpenClaw",
            updatedAt = null,
            model = null,
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
