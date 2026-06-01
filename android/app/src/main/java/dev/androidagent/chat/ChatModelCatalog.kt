package dev.androidagent.chat

import dev.androidagent.AgentConfig
import dev.androidagent.AgentModelOptions

data class ChatHarnessModelGroup(
    val id: String,
    val label: String,
    val models: List<ChatModelOption>
)

object ChatModelCatalog {
    fun mergeModelOptions(
        gatewayModels: List<ChatModelOption>,
        localLiteRtAvailable: Boolean,
        localModels: List<ChatModelOption> = emptyList(),
        enabledHarnessIds: Set<String> = defaultEnabledHarnessIds()
    ): List<ChatModelOption> {
        val byId = linkedMapOf<String, ChatModelOption>()
        if (AgentConfig.HARNESS_OPENCLAW in enabledHarnessIds) {
            AgentModelOptions.models.forEach { local ->
                byId[local.id] = ChatModelOption(
                    id = local.id,
                    label = local.label,
                    provider = null,
                    harnessId = AgentConfig.HARNESS_OPENCLAW,
                    harnessLabel = "OpenClaw",
                    modelId = local.id,
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                )
            }
        }
        gatewayModels.filter { modelHarnessId(it) in enabledHarnessIds }.forEach { remote ->
            byId[remote.id] = remote
        }
        if (localLiteRtAvailable && AgentConfig.HARNESS_LOCAL in enabledHarnessIds) {
            byId[AgentModelOptions.LOCAL_LITERT_MODEL_ID] = localModels.firstOrNull { it.id == AgentModelOptions.LOCAL_LITERT_MODEL_ID }
                ?.copy(
                    provider = "android",
                    harnessId = AgentConfig.HARNESS_LOCAL,
                    harnessLabel = "Local",
                    modelId = AgentModelOptions.LOCAL_LITERT_MODEL_ID
                )
                ?: ChatModelOption(
                    id = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                    label = "Local LiteRT-LM",
                    provider = "android",
                    harnessId = AgentConfig.HARNESS_LOCAL,
                    harnessLabel = "Local",
                    modelId = AgentModelOptions.LOCAL_LITERT_MODEL_ID,
                    contextWindow = null,
                    available = true,
                    reasoningOptions = null,
                    defaultReasoningEffort = null
                )
        } else {
            byId.remove(AgentModelOptions.LOCAL_LITERT_MODEL_ID)
        }
        return byId.values.toList()
    }

    fun modelPickerOptions(
        state: ChatState,
        localLiteRtAvailable: Boolean,
        enabledHarnessIds: Set<String> = defaultEnabledHarnessIds()
    ): List<ChatModelOption> {
        val hostModels = state.hostModels.ifEmpty {
            if (state.modelSource == ChatModelSource.LOCAL) emptyList() else state.models
        }
        return mergeModelOptions(
            gatewayModels = hostModels,
            localLiteRtAvailable = localLiteRtAvailable,
            localModels = state.localModels,
            enabledHarnessIds = enabledHarnessIds
        )
    }

    fun selectedModelId(
        selectedModel: String?,
        localLiteRtAvailable: Boolean,
        models: List<ChatModelOption> = emptyList()
    ): String {
        val normalized = if (selectedModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID && !localLiteRtAvailable) {
            AgentModelOptions.models.firstOrNull()?.id.orEmpty()
        } else {
            selectedModel.orEmpty()
        }
        if (models.isEmpty()) return normalized
        return normalized.takeIf { id -> models.any { it.id == id } }
            ?: models.firstOrNull { it.available != false }?.id
            ?: models.firstOrNull()?.id.orEmpty()
    }

    fun selectedModelForActiveHarness(
        current: String?,
        incoming: String?,
        activeHarnessId: String?
    ): String? {
        val activeHarness = normalizeHarnessId(activeHarnessId)
        val currentModel = current?.trim()?.takeIf { it.isNotBlank() }
        val incomingModel = normalizeModelForHarness(incoming, activeHarness)

        if (incomingModel == null) {
            return currentModel?.takeUnless { model ->
                activeHarness != null && harnessForModel(model) != activeHarness
            }
        }
        if (currentModel == null) return incomingModel

        val currentHarness = harnessForModel(currentModel)
        val incomingHarness = harnessForModel(incomingModel)
        if (activeHarness != null && currentHarness != activeHarness && incomingHarness == activeHarness) {
            return incomingModel
        }
        if (currentHarness != incomingHarness) {
            return incomingModel
        }
        return currentModel
    }

    fun normalizeModelForHarness(model: String?, harnessId: String?): String? {
        val cleanModel = model?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val harness = normalizeHarnessId(harnessId)
        if (cleanModel == AgentModelOptions.LOCAL_LITERT_MODEL_ID) return cleanModel
        if (
            (
                harness == AgentConfig.HARNESS_CODEX ||
                    harness == AgentConfig.HARNESS_HERMES ||
                    harness == AgentConfig.HARNESS_OPENCODE ||
                    harness == AgentConfig.HARNESS_PI
                ) &&
            harnessFromModelPrefix(cleanModel) == null
        ) {
            return "$harness:$cleanModel"
        }
        return cleanModel
    }

    fun harnessModelGroups(
        models: List<ChatModelOption>,
        enabledHarnessIds: Set<String> = defaultEnabledHarnessIds()
    ): List<ChatHarnessModelGroup> {
        return models
            .filter { model -> model.available != false }
            .filter { model -> modelHarnessId(model) in enabledHarnessIds }
            .groupBy { model -> modelHarnessId(model) }
            .map { (harnessId, harnessModels) ->
                ChatHarnessModelGroup(
                    id = harnessId,
                    label = harnessModels.firstOrNull()?.let(::modelHarnessLabel) ?: harnessLabel(harnessId),
                    models = harnessModels
                )
            }
            .sortedWith(compareBy<ChatHarnessModelGroup> { modelHarnessSortOrder(it.id) }.thenBy { it.label })
    }

    fun modelsForHarness(
        harnessId: String?,
        models: List<ChatModelOption>,
        enabledHarnessIds: Set<String> = defaultEnabledHarnessIds()
    ): List<ChatModelOption> {
        val normalizedHarness = normalizeHarnessId(harnessId) ?: return emptyList()
        if (normalizedHarness !in enabledHarnessIds) return emptyList()
        return models.filter { model ->
            model.available != false && modelHarnessId(model) == normalizedHarness
        }
    }

    fun defaultModelForHarness(
        harnessId: String?,
        configuredDefaultModel: String?,
        models: List<ChatModelOption>,
        enabledHarnessIds: Set<String> = defaultEnabledHarnessIds()
    ): String? {
        val normalizedHarness = normalizeHarnessId(harnessId) ?: return null
        val harnessModels = modelsForHarness(normalizedHarness, models, enabledHarnessIds)
        if (harnessModels.isEmpty()) return null

        val configured = configuredDefaultModel?.trim()?.takeIf { it.isNotBlank() }
        val normalizedDefault = normalizeModelForHarness(configured, normalizedHarness)
        val candidates = listOfNotNull(configured, normalizedDefault).distinct()
        val configuredMatch = candidates.firstNotNullOfOrNull { candidate ->
            harnessModels.firstOrNull { model ->
                model.id == candidate || model.modelId == candidate
            }?.id
        }
        return configuredMatch ?: harnessModels.firstOrNull()?.id
    }

    fun harnessForModel(model: String): String {
        if (model == AgentModelOptions.LOCAL_LITERT_MODEL_ID) return AgentConfig.HARNESS_LOCAL
        return harnessFromModelPrefix(model) ?: AgentConfig.HARNESS_OPENCLAW
    }

    fun harnessFromSessionKey(sessionKey: String?): String? {
        val cleanKey = sessionKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val prefix = cleanKey.substringBefore(":", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.lowercase()
        return when (prefix) {
            AgentConfig.HARNESS_CODEX,
            AgentConfig.HARNESS_HERMES,
            AgentConfig.HARNESS_OPENCODE,
            AgentConfig.HARNESS_PI,
            AgentConfig.HARNESS_LOCAL -> prefix
            else -> AgentConfig.HARNESS_OPENCLAW
        }
    }

    fun normalizeHarnessId(harnessId: String?): String? {
        val cleanHarnessId = harnessId?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        return when (cleanHarnessId) {
            AgentConfig.HARNESS_OPENCLAW,
            AgentConfig.HARNESS_CODEX,
            AgentConfig.HARNESS_HERMES,
            AgentConfig.HARNESS_OPENCODE,
            AgentConfig.HARNESS_PI,
            AgentConfig.HARNESS_LOCAL -> cleanHarnessId
            else -> null
        }
    }

    fun contextWindowForModel(state: ChatState, selectedModel: String?): Long? {
        val model = selectedModel?.takeIf { it.isNotBlank() } ?: return null
        val options = state.models + state.hostModels + state.localModels
        return options.firstOrNull { option ->
            option.id == model || option.modelId == model
        }?.contextWindow
    }

    fun modelHarnessLabel(model: ChatModelOption): String {
        return model.harnessLabel?.takeIf { it.isNotBlank() }
            ?: when (model.harnessId?.lowercase()) {
                AgentConfig.HARNESS_OPENCLAW -> "OpenClaw"
                AgentConfig.HARNESS_HERMES -> "Hermes"
                AgentConfig.HARNESS_CODEX -> "Codex"
                AgentConfig.HARNESS_OPENCODE -> "OpenCode"
                AgentConfig.HARNESS_PI -> "Pi"
                AgentConfig.HARNESS_LOCAL -> "Local"
                else -> model.provider?.takeIf { it.isNotBlank() } ?: "OpenClaw"
            }
    }

    fun modelHarnessId(model: ChatModelOption): String {
        return model.harnessId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: when (model.provider?.lowercase()) {
                "hermes" -> AgentConfig.HARNESS_HERMES
                "codex" -> AgentConfig.HARNESS_CODEX
                "opencode" -> AgentConfig.HARNESS_OPENCODE
                "pi" -> AgentConfig.HARNESS_PI
                "android" -> AgentConfig.HARNESS_LOCAL
                else -> AgentConfig.HARNESS_OPENCLAW
            }
    }

    fun modelHarnessSortOrder(harnessId: String): Int {
        return when (harnessId.lowercase()) {
            AgentConfig.HARNESS_OPENCLAW -> 0
            AgentConfig.HARNESS_HERMES -> 1
            AgentConfig.HARNESS_CODEX -> 2
            AgentConfig.HARNESS_OPENCODE -> 3
            AgentConfig.HARNESS_PI -> 4
            AgentConfig.HARNESS_LOCAL -> 5
            else -> 6
        }
    }

    fun harnessLabel(harnessId: String): String {
        return when (harnessId.lowercase()) {
            AgentConfig.HARNESS_OPENCLAW -> "OpenClaw"
            AgentConfig.HARNESS_HERMES -> "Hermes"
            AgentConfig.HARNESS_CODEX -> "Codex"
            AgentConfig.HARNESS_OPENCODE -> "OpenCode"
            AgentConfig.HARNESS_PI -> "Pi"
            AgentConfig.HARNESS_LOCAL -> "Local"
            else -> harnessId
        }
    }

    fun defaultEnabledHarnessIds(): Set<String> = setOf(
        AgentConfig.HARNESS_OPENCLAW,
        AgentConfig.HARNESS_HERMES,
        AgentConfig.HARNESS_CODEX,
        AgentConfig.HARNESS_OPENCODE,
        AgentConfig.HARNESS_PI,
        AgentConfig.HARNESS_LOCAL
    )

    private fun harnessFromModelPrefix(model: String): String? {
        val prefix = model.trim().substringBefore(":", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.lowercase()
        return normalizeHarnessId(prefix)
    }
}
