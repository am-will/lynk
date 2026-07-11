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
    fun blankWorkspaceRemainsNoDefaultWorkspace() {
        assertEquals("", HostWorkspacePaths.normalizeInput(""))
        assertEquals("", HostWorkspacePaths.normalizeInput("   "))
        assertEquals("", HostWorkspacePaths.editorText(""))
        assertEquals("No default workspace", HostWorkspacePaths.defaultWorkspaceLabel(""))
        assertFalse(HostWorkspacePaths.hasDefault(""))
    }

    @Test
    fun explicitHomeWorkspaceIsPreserved() {
        assertEquals("~/", HostWorkspacePaths.normalizeInput("~"))
        assertEquals("~/", HostWorkspacePaths.normalizeInput("~/"))
        assertEquals("~/Projects", HostWorkspacePaths.normalizeInput("/Users/example/Projects"))
        assertTrue(HostWorkspacePaths.hasDefault("~/"))
    }

    @Test
    fun requiredHomeWorkspaceInputAlwaysKeepsHomePrefix() {
        assertEquals("~/", HostWorkspacePaths.requiredHomeEditorText(""))
        assertEquals("~/", HostWorkspacePaths.normalizeRequiredHomeInput(""))
        assertEquals("~/", HostWorkspacePaths.requireHomePrefix("~"))
        assertEquals("~/Applications/project", HostWorkspacePaths.requireHomePrefix("Applications/project"))
        assertEquals("~/Projects", HostWorkspacePaths.normalizeRequiredHomeInput("/Users/example/Projects"))
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
        assertTrue(config.piHarnessEnabled)
        assertTrue(config.devinHarnessEnabled)
        assertTrue(config.isModelHarnessEnabled("openclaw"))
        assertTrue(config.isModelHarnessEnabled("hermes"))
        assertTrue(config.isModelHarnessEnabled("codex"))
        assertTrue(config.isModelHarnessEnabled("opencode"))
        assertTrue(config.isModelHarnessEnabled("pi"))
        assertTrue(config.isModelHarnessEnabled("devin"))
        assertFalse(config.isModelHarnessEnabled("local"))
    }

    @Test
    fun hostHarnessDescriptorsDefineSettingsOrderAndWorkspaceSupport() {
        assertEquals(
            listOf("openclaw", "hermes", "codex", "opencode", "pi", "devin"),
            AgentConfig.HOST_HARNESSES.map { it.id }
        )
        assertEquals(
            listOf("OpenClaw", "Hermes", "Codex", "OpenCode", "Pi", "Devin"),
            AgentConfig.HOST_HARNESSES.map { it.label }
        )
        assertFalse(AgentConfig.isWorkspaceHarness("openclaw"))
        assertFalse(AgentConfig.isWorkspaceHarness("hermes"))
        assertTrue(AgentConfig.isWorkspaceHarness("codex"))
        assertTrue(AgentConfig.isWorkspaceHarness("opencode"))
        assertTrue(AgentConfig.isWorkspaceHarness("pi"))
        assertTrue(AgentConfig.isWorkspaceHarness("devin"))
        assertFalse(AgentConfig.isWorkspaceHarness("local"))
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
        assertEquals(null, config.defaultModelForHarness("pi"))
        assertEquals(null, config.defaultModelForHarness("devin"))
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
            opencodeDefaultModel = " opencode:openai/gpt-5.5 ",
            piDefaultModel = " pi:anthropic/claude-sonnet-4-5 ",
            devinDefaultModel = " devin:claude-3-7-sonnet-20250219 "
        )

        assertEquals("gpt-5.4", config.defaultModelForHarness("openclaw"))
        assertEquals("hermes:qwen", config.defaultModelForHarness("hermes"))
        assertEquals("codex:gpt-5.3-codex", config.defaultModelForHarness("codex"))
        assertEquals("opencode:openai/gpt-5.5", config.defaultModelForHarness("opencode"))
        assertEquals("pi:anthropic/claude-sonnet-4-5", config.defaultModelForHarness("pi"))
        assertEquals("devin:claude-3-7-sonnet-20250219", config.defaultModelForHarness("devin"))
        assertEquals(null, config.defaultModelForHarness("local"))
    }

    @Test
    fun workspacePathLookupIsHarnessSpecific() {
        val config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "openclaw-agent",
            token = "",
            openAiApiKey = "",
            systemPrompt = "prompt",
            model = "gpt-5.5",
            reasoningEffort = "medium",
            workspacePaths = mapOf(
                "codex" to "~/Projects",
                "opencode" to "",
                "pi" to "",
                "devin" to "~/DevinWorkspace"
            )
        )

        assertEquals(mapOf("codex" to "~/Projects", "opencode" to "", "pi" to "", "devin" to "~/DevinWorkspace"), config.workspacePaths)
        assertEquals("~/Projects", config.workspacePathForHarness("codex"))
        assertEquals("~/DevinWorkspace", config.workspacePathForHarness("devin"))
        assertEquals("", config.workspacePathForHarness("opencode"))
        assertEquals("", config.workspacePathForHarness("pi"))

        val updated = config.withWorkspacePath("devin", "~/New")
        assertEquals("~/New", updated.workspacePathForHarness("devin"))
        assertEquals("~/Projects", updated.workspacePathForHarness("codex"))
    }

    @Test
    fun legacyWorkspacePreferenceValuesMigrateWithoutChangingPathsOrDefaults() {
        assertEquals(
            mapOf(
                "codex" to "~/ExistingCodex",
                "opencode" to "/Users/example/ExistingOpenCode",
                "pi" to "",
                "devin" to "~/ExistingDevin"
            ),
            migratedWorkspacePaths(
                codex = "~/ExistingCodex",
                openCode = "/Users/example/ExistingOpenCode",
                pi = null,
                devin = "~/ExistingDevin"
            )
        )
    }

    @Test
    fun workspaceCreationConfirmationUsesHarnessSpecificCopyAndExactPath() {
        val confirmation = HostWorkspacePaths.creationConfirmation(
            harnessId = AgentConfig.HARNESS_DEVIN,
            path = "~/Projects/new-devin-workspace"
        )

        assertEquals("Folder not found for Devin. Would you like to create it?", confirmation.message)
        assertEquals("~/Projects/new-devin-workspace", confirmation.path)
    }
}
