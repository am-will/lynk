package dev.androidagent.settings

import dev.androidagent.chat.ChatModelOption

data class DiagnosticsBackendAvailability(
    val modelCounts: Map<String, Int> = emptyMap(),
    val activeHarnessIds: Set<String> = emptySet(),
    val modelsByHarness: Map<String, List<ChatModelOption>> = emptyMap(),
    val updatedAtMs: Long = 0L
) {
    fun isReady(harnessId: String): Boolean =
        modelCounts.getOrDefault(harnessId, 0) > 0 || harnessId in activeHarnessIds
}

object DiagnosticsBackendSnapshot {
    @Volatile
    private var availability = DiagnosticsBackendAvailability()

    fun update(
        modelCounts: Map<String, Int>,
        activeHarnessIds: Set<String>,
        modelsByHarness: Map<String, List<ChatModelOption>> = emptyMap()
    ) {
        availability = DiagnosticsBackendAvailability(
            modelCounts = modelCounts,
            activeHarnessIds = activeHarnessIds,
            modelsByHarness = modelsByHarness,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun current(): DiagnosticsBackendAvailability = availability
}
