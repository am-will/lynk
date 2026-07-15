package dev.androidagent.overlay

import dev.androidagent.chat.ChatModelOption
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPickerFuzzySearchTest {
    @Test
    fun blankQueryPreservesCatalogOrder() {
        val models = listOf(
            model("hermes:zeta", "Zeta"),
            model("hermes:alpha", "Alpha")
        )

        assertEquals(models, ModelPickerFuzzySearch.filter(models, "   "))
    }

    @Test
    fun searchesLabelsIdsModelIdsAndProviders() {
        val claude = model(
            id = "hermes:anthropic/claude-3-7-sonnet",
            label = "Claude 3.7 Sonnet",
            provider = "Anthropic",
            modelId = "anthropic/claude-3-7-sonnet"
        )
        val qwen = model(
            id = "hermes:qwen/qwen3-coder",
            label = "Qwen3 Coder",
            provider = "Alibaba",
            modelId = "qwen/qwen3-coder"
        )
        val models = listOf(claude, qwen)

        assertEquals(listOf(claude), ModelPickerFuzzySearch.filter(models, "sonnet"))
        assertEquals(listOf(qwen), ModelPickerFuzzySearch.filter(models, "qwen3coder"))
        assertEquals(listOf(claude), ModelPickerFuzzySearch.filter(models, "anthrpic"))
    }

    @Test
    fun fuzzySubsequenceAndTyposMatchWhileKeepingEqualMatchesStable() {
        val sonnet = model("hermes:claude-3-7-sonnet", "Claude 3.7 Sonnet")
        val opus = model("hermes:claude-3-opus", "Claude 3 Opus")

        assertEquals(listOf(sonnet), ModelPickerFuzzySearch.filter(listOf(opus, sonnet), "cldsn"))
        assertEquals(
            listOf(opus, sonnet),
            ModelPickerFuzzySearch.filter(listOf(opus, sonnet), "cluade")
        )
    }

    @Test
    fun unmatchedQueryReturnsNoModels() {
        val models = listOf(model("hermes:qwen", "Qwen"))

        assertEquals(emptyList<ChatModelOption>(), ModelPickerFuzzySearch.filter(models, "zzzzzz"))
    }

    @Test
    fun searchQueriesRemainIsolatedPerHarness() {
        val state = ModelPickerSearchState()

        state.update("hermes", "claude")
        state.update("codex", "gpt")

        assertEquals("claude", state.queryFor("hermes"))
        assertEquals("gpt", state.queryFor("codex"))
        assertEquals("", state.queryFor("openclaw"))

        state.update("hermes", "  ")
        assertEquals("", state.queryFor("hermes"))
        assertEquals("gpt", state.queryFor("codex"))
    }

    private fun model(
        id: String,
        label: String,
        provider: String? = null,
        modelId: String? = id.substringAfter(':')
    ) = ChatModelOption(
        id = id,
        label = label,
        provider = provider,
        harnessId = "hermes",
        harnessLabel = "Hermes",
        modelId = modelId,
        contextWindow = null,
        available = true,
        reasoningOptions = null,
        defaultReasoningEffort = null
    )
}
