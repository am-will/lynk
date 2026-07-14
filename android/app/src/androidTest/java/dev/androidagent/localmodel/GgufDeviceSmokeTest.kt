package dev.androidagent.localmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.AgentConfig
import dev.androidagent.LocalModelBackend
import dev.androidagent.localmodel.gguf.GgufNative
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GgufDeviceSmokeTest {
    @Test
    fun loadsConfiguredModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPath") != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = requireNotNull(arguments.getString("modelPath"))
        val backend = LocalModelBackend.fromKey(arguments.getString("backend"))
        val contextTokens = arguments.getString("contextTokens")?.toIntOrNull() ?: 4096
        GgufNative.ensureLoaded()
        val handle = GgufNative.create(modelPath, contextTokens, gpuLayersFor(backend), backend.key)
        try {
            println("GGUF_LOAD_STATUS=${GgufNative.getContextSize(handle)} tokens, ${GgufNative.getBackendName(handle)}")
            assertTrue(handle != 0L)
        } finally {
            GgufNative.close(handle)
        }
    }

    @Test
    fun generatesNativeWithTokenLimit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPath") != null)
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = requireNotNull(arguments.getString("modelPath"))
        val backend = LocalModelBackend.fromKey(arguments.getString("backend"))
        val contextTokens = arguments.getString("contextTokens")?.toIntOrNull() ?: 4096
        val maxOutputTokens = arguments.getString("maxOutputTokens")?.toIntOrNull() ?: 16
        val prompt = arguments.getString("prompt") ?: "Reply with exactly BONSAI_OK"
        GgufNative.ensureLoaded()
        val handle = GgufNative.create(modelPath, contextTokens, gpuLayersFor(backend), backend.key)
        val output = try {
            GgufNative.generate(
                handle,
                "Follow the user instruction exactly.",
                prompt,
                maxOutputTokens,
                0.7f,
                0.95f,
                20,
                object : GgufNative.Callback {
                    override fun onDelta(delta: String) = Unit
                    override fun isCancelled(): Boolean = false
                }
            )
        } finally {
            GgufNative.close(handle)
        }
        println("GGUF_NATIVE_RESULT=$output")
        assertTrue(output.isNotBlank())
    }

    @Test
    fun generatesFromConfiguredModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelPath") != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = requireNotNull(arguments.getString("modelPath"))
        val backend = LocalModelBackend.fromKey(arguments.getString("backend"))
        val contextTokens = arguments.getString("contextTokens")?.toIntOrNull() ?: 4096
        val prompt = arguments.getString("prompt") ?: "Reply with exactly BONSAI_OK"
        val statuses = mutableListOf<String>()
        val runtime = GgufRuntime(instrumentation.targetContext)
        val output = try {
            withTimeout(15 * 60 * 1000L) {
                runtime.generate(
                    LocalModelRequest(
                        prompt = prompt,
                        systemPrompt = "Follow the user instruction exactly.",
                        config = config(modelPath, backend, contextTokens)
                    ),
                    onDelta = {},
                    onStatus = statuses::add
                )
            }
        } finally {
            runtime.close()
        }
        println("GGUF_SMOKE_STATUS=${statuses.joinToString(" | ")}")
        println("GGUF_SMOKE_RESULT=$output")
        assertTrue(output.isNotBlank())
    }

    private fun config(path: String, backend: LocalModelBackend, contextTokens: Int) = AgentConfig(
        hostUrl = "ws://127.0.0.1:8788/phone",
        deviceId = "device-test",
        token = "test-token",
        openAiApiKey = "",
        systemPrompt = "",
        model = "local-litertlm",
        reasoningEffort = "medium",
        experimentalLocalModelsEnabled = true,
        localModelPath = path,
        localModelBackend = backend,
        localContextTokens = contextTokens
    )

}
