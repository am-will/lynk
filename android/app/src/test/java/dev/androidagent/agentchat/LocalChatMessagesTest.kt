package dev.androidagent.agentchat

import dev.androidagent.AgentConfig
import dev.androidagent.localmodel.LocalChatMessage
import dev.androidagent.localmodel.LocalChatSession
import dev.androidagent.localmodel.LocalModelRuntimeKind
import dev.androidagent.localmodel.LocalModelRuntimeProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatMessagesTest {
    @Test
    fun modelsAdvertiseEffectiveRuntimeContext() {
        val message = LocalChatMessages.models(profile(contextTokens = 4_096))

        assertEquals(4_096, message.getJSONArray("models").getJSONObject(0).getInt("contextWindow"))
    }

    @Test
    fun usageReportsEffectiveRuntimeContext() {
        val message = LocalChatMessages.usage(
            session = session(),
            config = config(contextTokens = 65_536),
            runtimeProfile = profile(contextTokens = 4_096),
            toolDescriptionsJson = "[]"
        )

        assertEquals(4_096L, message.getJSONObject("usage").getLong("contextTokens"))
    }

    @Test
    fun sessionMetadataReportsEffectiveRuntimeContext() {
        val session = session()
        val message = LocalChatMessages.sessions(
            selectedKey = session.key,
            sessions = listOf(session),
            config = config(contextTokens = 65_536),
            runtimeProfile = profile(contextTokens = 4_096),
            toolDescriptionsJson = "[]"
        )

        assertEquals(4_096L, message.getJSONArray("sessions").getJSONObject(0).getLong("contextTokens"))
    }

    private fun profile(contextTokens: Int) = LocalModelRuntimeProfile(
        kind = LocalModelRuntimeKind.Gguf,
        effectiveContextTokens = contextTokens,
        supportsImageInput = false
    )

    private fun config(contextTokens: Int) = AgentConfig(
        hostUrl = "ws://127.0.0.1:8788/phone",
        deviceId = "phone",
        token = "token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "local-litertlm",
        reasoningEffort = "medium",
        localContextTokens = contextTokens
    )

    private fun session() = LocalChatSession(
        key = "local:main",
        label = "Local chat",
        updatedAt = 2L,
        messages = listOf(
            LocalChatMessage("user", "user", "hello", 1L),
            LocalChatMessage("assistant", "assistant", "hi", 2L)
        )
    )
}
