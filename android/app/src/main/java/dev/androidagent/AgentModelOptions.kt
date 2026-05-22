package dev.androidagent

data class ModelOption(val id: String, val label: String)
data class ReasoningOption(val id: String, val label: String)

object AgentModelOptions {
    const val LOCAL_LITERT_MODEL_ID = "local-litertlm"

    val models = listOf(
        ModelOption("gpt-5.5", "gpt-5.5"),
        ModelOption("gpt-5.4", "gpt-5.4"),
        ModelOption("gpt-5.4-mini", "gpt-5.4-mini"),
        ModelOption("gpt-5.3-codex", "gpt-5.3-codex"),
        ModelOption("gpt-5.2", "gpt-5.2")
    )

    val reasoningEfforts = listOf(
        ReasoningOption("none", "None"),
        ReasoningOption("minimal", "Minimal"),
        ReasoningOption("low", "Low"),
        ReasoningOption("medium", "Medium"),
        ReasoningOption("high", "High"),
        ReasoningOption("xhigh", "Extra high")
    )

    fun modelLabel(id: String): String = models.firstOrNull { it.id == id }?.label ?: id
    fun reasoningLabel(id: String): String = reasoningEfforts.firstOrNull { it.id == id }?.label ?: id
}
