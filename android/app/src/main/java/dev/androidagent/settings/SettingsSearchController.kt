package dev.androidagent.settings

data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val group: String,
    val destination: SettingsDestination
)

enum class SettingsDestination {
    Runtime,
    Connection,
    Voice,
    Safety,
    Appearance,
    LocalModel
}

object SettingsSearchController {

    private val entries = listOf(
        SettingsSearchEntry("runtime", "Runtime", "Host bridge, local model, backends", "Categories", SettingsDestination.Runtime),
        SettingsSearchEntry("connection", "Connection", "URL, pairing, network, transport", "Categories", SettingsDestination.Connection),
        SettingsSearchEntry("voice", "Voice", "Realtime voice, transcription, audio", "Categories", SettingsDestination.Voice),
        SettingsSearchEntry("safety", "Safety", "Confirmations, guardrails, system prompt", "Categories", SettingsDestination.Safety),
        SettingsSearchEntry("appearance", "Appearance", "Theme, bubble, font size", "Categories", SettingsDestination.Appearance),
        SettingsSearchEntry("local", "Local model", "Import LiteRT-LM, backend, context", "Categories", SettingsDestination.LocalModel),
        SettingsSearchEntry("import", "Import model", "Import a .litertlm file", "Actions", SettingsDestination.LocalModel),
        SettingsSearchEntry("overlay", "Grant overlay", "Open overlay permission settings", "Actions", SettingsDestination.Connection),
        SettingsSearchEntry("accessibility", "Open accessibility", "Enable phone control service", "Actions", SettingsDestination.Connection),
        SettingsSearchEntry("token", "Auth token", "Bridge pairing token", "Actions", SettingsDestination.Connection),
        SettingsSearchEntry("openai", "OpenAI API key", "Realtime voice key", "Actions", SettingsDestination.Voice),
        SettingsSearchEntry("prompt", "System prompt", "Edit agent instructions", "Actions", SettingsDestination.Safety),
        SettingsSearchEntry("bubble", "Bubble size", "Resize floating bubble", "Actions", SettingsDestination.Appearance),
        SettingsSearchEntry("harness_openclaw", "OpenClaw", "Host harness backend", "Backends", SettingsDestination.Runtime),
        SettingsSearchEntry("harness_hermes", "Hermes", "Host harness backend", "Backends", SettingsDestination.Runtime),
        SettingsSearchEntry("harness_codex", "Codex", "Host harness backend", "Backends", SettingsDestination.Runtime),
        SettingsSearchEntry("harness_local", "Local LiteRT-LM", "On-device model", "Backends", SettingsDestination.LocalModel),
        SettingsSearchEntry("run_host", "Host bridge", "Run agents on PC bridge", "Run target", SettingsDestination.Runtime),
        SettingsSearchEntry("run_local", "Local phone", "Run on-device model", "Run target", SettingsDestination.LocalModel)
    )

    fun filter(query: String): List<SettingsSearchEntry> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) {
            return entries
        }
        return entries.filter { entry ->
            entry.title.lowercase().contains(normalized) ||
                entry.subtitle.lowercase().contains(normalized) ||
                entry.group.lowercase().contains(normalized) ||
                entry.id.lowercase().contains(normalized)
        }
    }
}
