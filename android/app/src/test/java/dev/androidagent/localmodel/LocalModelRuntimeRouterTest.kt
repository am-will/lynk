package dev.androidagent.localmodel

import dev.androidagent.AgentConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRuntimeRouterTest {
    @Test
    fun litertlmRequestRoutesToLiteRtRuntime() = runBlocking {
        val liteRt = RecordingRuntime()
        val gguf = RecordingRuntime()
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })
        val response = router.generate(request("hello", "model.litertlm"), {}, {})
        assertEquals("hello", response)
        assertEquals(listOf("hello"), liteRt.prompts)
        assertTrue(gguf.prompts.isEmpty())
    }

    @Test
    fun ggufRequestRoutesToGgufRuntime() = runBlocking {
        val liteRt = RecordingRuntime()
        val gguf = RecordingRuntime()
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })
        val response = router.generate(request("hi", "model.gguf"), {}, {})
        assertEquals("hi", response)
        assertEquals(listOf("hi"), gguf.prompts)
        assertTrue(liteRt.prompts.isEmpty())
    }

    @Test
    fun unsupportedExtensionThrowsWithoutInitializingRuntimes() = runBlocking {
        val liteRt = RecordingRuntime()
        val gguf = RecordingRuntime()
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })
        val result = runCatching { router.generate(request("x", "model.bin"), {}, {}) }
        assertTrue(result.exceptionOrNull() is UnsupportedLocalModelPathException)
        assertTrue(liteRt.prompts.isEmpty())
        assertTrue(gguf.prompts.isEmpty())
    }

    @Test
    fun multipleSameExtensionRequestsReuseSameRuntime() = runBlocking {
        val liteRt = RecordingRuntime()
        val gguf = RecordingRuntime()
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })
        router.generate(request("a", "model.litertlm"), {}, {})
        router.generate(request("b", "model.litertlm"), {}, {})
        assertEquals(listOf("a", "b"), liteRt.prompts)
        assertEquals(1, liteRt.created)
        assertTrue(gguf.prompts.isEmpty())
    }

    @Test
    fun closeClosesAllInitializedRuntimes() = runBlocking {
        val liteRt = RecordingRuntime()
        val gguf = RecordingRuntime()
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })
        router.generate(request("a", "model.litertlm"), {}, {})
        router.generate(request("b", "model.gguf"), {}, {})
        router.close()
        assertTrue(liteRt.closed)
        assertTrue(gguf.closed)
    }

    @Test
    fun factoryIsLazyAndOnlyCalledForUsedExtension() = runBlocking {
        var liteRtCreated = 0
        var ggufCreated = 0
        val router = LocalModelRuntimeRouter(
            { liteRtCreated++; RecordingRuntime() },
            { ggufCreated++; RecordingRuntime() }
        )
        router.generate(request("a", "model.litertlm"), {}, {})
        assertEquals(1, liteRtCreated)
        assertEquals(0, ggufCreated)
    }

    @Test
    fun profileComesFromSelectedRuntime() {
        val liteRt = RecordingRuntime(LocalModelRuntimeKind.LiteRtLm, supportsImageInput = true)
        val gguf = RecordingRuntime(LocalModelRuntimeKind.Gguf, supportsImageInput = false)
        val router = LocalModelRuntimeRouter({ liteRt }, { gguf })

        val profile = router.profile(request("x", "model.gguf").config)

        assertEquals(LocalModelRuntimeKind.Gguf, profile.kind)
        assertEquals(2048, profile.effectiveContextTokens)
        assertEquals(false, profile.supportsImageInput)
    }

    private fun request(prompt: String, path: String) = LocalModelRequest(
        prompt = prompt,
        systemPrompt = "system",
        config = AgentConfig(
            hostUrl = "ws://127.0.0.1:8788/phone",
            deviceId = "phone",
            token = "token",
            openAiApiKey = "",
            systemPrompt = "",
            model = "local-litertlm",
            reasoningEffort = "medium",
            localModelPath = path
        )
    )

    private class RecordingRuntime(
        private val kind: LocalModelRuntimeKind = LocalModelRuntimeKind.LiteRtLm,
        private val supportsImageInput: Boolean = true
    ) : LocalModelRuntime {
        val prompts = mutableListOf<String>()
        var closed = false
        var created = 0

        init {
            created++
        }

        override fun profile(config: AgentConfig): LocalModelRuntimeProfile = LocalModelRuntimeProfile(
            kind = kind,
            effectiveContextTokens = 2048,
            supportsImageInput = supportsImageInput
        )

        override suspend fun generate(
            request: LocalModelRequest,
            onDelta: suspend (String) -> Unit,
            onStatus: suspend (String) -> Unit
        ): String {
            prompts.add(request.prompt)
            return request.prompt
        }

        override fun close() {
            closed = true
        }
    }
}
