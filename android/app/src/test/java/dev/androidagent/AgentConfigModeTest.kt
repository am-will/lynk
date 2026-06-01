package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigModeTest {
    @Test
    fun unknownAgentModeDefaultsToHost() {
        assertEquals(AgentMode.Host, AgentMode.fromKey("unexpected"))
        assertEquals(AgentMode.Host, AgentMode.fromKey(null))
    }

    @Test
    fun unknownLocalBackendDefaultsToCpu() {
        assertEquals(LocalModelBackend.Cpu, LocalModelBackend.fromKey("unexpected"))
        assertEquals(LocalModelBackend.Cpu, LocalModelBackend.fromKey(null))
    }

    @Test
    fun unknownActiveSendModeDefaultsToSteer() {
        assertEquals(ChatActiveSendMode.Steer, ChatActiveSendMode.fromKey("unexpected"))
        assertEquals(ChatActiveSendMode.Steer, ChatActiveSendMode.fromKey(null))
    }

    @Test
    fun blankCodexWorkspaceRemainsNoDefaultWorkspace() {
        assertEquals("", CodexWorkspacePaths.normalizeInput(""))
        assertEquals("", CodexWorkspacePaths.normalizeInput("   "))
        assertEquals("", CodexWorkspacePaths.editorText(""))
        assertEquals("No default workspace", CodexWorkspacePaths.defaultWorkspaceLabel(""))
        assertFalse(CodexWorkspacePaths.hasDefault(""))
    }

    @Test
    fun explicitCodexHomeWorkspaceIsPreserved() {
        assertEquals("~/", CodexWorkspacePaths.normalizeInput("~"))
        assertEquals("~/", CodexWorkspacePaths.normalizeInput("~/"))
        assertEquals("~/Projects", CodexWorkspacePaths.normalizeInput("/Users/example/Projects"))
        assertTrue(CodexWorkspacePaths.hasDefault("~/"))
    }

    @Test
    fun experimentalLocalModelsAreDisabledByDefault() {
        val config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "openclaw-agent",
            token = "",
            openAiApiKey = "",
            systemPrompt = "prompt",
            model = "gpt-5.5",
            reasoningEffort = "medium"
        )

        assertFalse(config.experimentalLocalModelsEnabled)
        assertEquals(ChatActiveSendMode.Steer, config.activeSendMode)
    }

    @Test
    fun hostHarnessesAreEnabledByDefault() {
        val config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "openclaw-agent",
            token = "",
            openAiApiKey = "",
            systemPrompt = "prompt",
            model = "gpt-5.5",
            reasoningEffort = "medium"
        )

        assertTrue(config.openClawHarnessEnabled)
        assertTrue(config.hermesHarnessEnabled)
        assertTrue(config.codexHarnessEnabled)
        assertTrue(config.opencodeHarnessEnabled)
        assertTrue(config.isModelHarnessEnabled("openclaw"))
        assertTrue(config.isModelHarnessEnabled("hermes"))
        assertTrue(config.isModelHarnessEnabled("codex"))
        assertTrue(config.isModelHarnessEnabled("opencode"))
        assertFalse(config.isModelHarnessEnabled("local"))
    }

    @Test
    fun harnessDefaultModelsAreBlankUntilConfigured() {
        val config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "openclaw-agent",
            token = "",
            openAiApiKey = "",
            systemPrompt = "prompt",
            model = "gpt-5.5",
            reasoningEffort = "medium"
        )

        assertEquals(null, config.defaultModelForHarness("openclaw"))
        assertEquals(null, config.defaultModelForHarness("hermes"))
        assertEquals(null, config.defaultModelForHarness("codex"))
        assertEquals(null, config.defaultModelForHarness("opencode"))
    }

    @Test
    fun harnessDefaultModelLookupTrimsConfiguredValues() {
        val config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "openclaw-agent",
            token = "",
            openAiApiKey = "",
            systemPrompt = "prompt",
            model = "gpt-5.5",
            reasoningEffort = "medium",
            openClawDefaultModel = " gpt-5.4 ",
            hermesDefaultModel = " hermes:qwen ",
            codexDefaultModel = " codex:gpt-5.3-codex ",
            opencodeDefaultModel = " opencode:openai/gpt-5.5 "
        )

        assertEquals("gpt-5.4", config.defaultModelForHarness("openclaw"))
        assertEquals("hermes:qwen", config.defaultModelForHarness("hermes"))
        assertEquals("codex:gpt-5.3-codex", config.defaultModelForHarness("codex"))
        assertEquals("opencode:openai/gpt-5.5", config.defaultModelForHarness("opencode"))
        assertEquals(null, config.defaultModelForHarness("local"))
    }
}
