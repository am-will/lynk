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
        assertTrue(config.isModelHarnessEnabled("openclaw"))
        assertTrue(config.isModelHarnessEnabled("hermes"))
        assertTrue(config.isModelHarnessEnabled("codex"))
        assertFalse(config.isModelHarnessEnabled("local"))
    }
}
