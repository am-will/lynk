package dev.androidagent.overlay

import dev.androidagent.AgentModelOptions
import dev.androidagent.chat.ChatModelOption
import dev.androidagent.chat.ChatSessionRow
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
}
