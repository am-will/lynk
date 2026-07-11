package dev.androidagent

data class WorkspaceCreationConfirmation(
    val message: String,
    val path: String
)

object HostWorkspacePaths {
    private val macHomePrefix = Regex("^/Users/[^/]+(?=/|$)")

    fun hasDefault(path: String?): Boolean = !path?.trim().isNullOrBlank()

    fun display(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isBlank()) return "~/"
        if (trimmed == "~" || trimmed.startsWith("~/")) return trimmed
        return macHomePrefix.replaceFirst(trimmed, "~")
    }

    fun defaultWorkspaceLabel(path: String?): String {
        return if (hasDefault(path)) display(path) else "No default workspace"
    }

    fun editorText(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        return if (trimmed.isBlank()) "" else display(trimmed)
    }

    fun requiredHomeEditorText(path: String?): String {
        return requireHomePrefix(display(path))
    }

    fun normalizeInput(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        return when {
            trimmed.isBlank() -> ""
            trimmed == "~" -> "~/"
            else -> display(trimmed)
        }
    }

    fun normalizeRequiredHomeInput(path: String?): String {
        return requireHomePrefix(path)
    }

    fun creationConfirmation(harnessId: String, path: String): WorkspaceCreationConfirmation {
        val label = AgentConfig.HOST_HARNESSES
            .firstOrNull { it.id == harnessId.lowercase() }
            ?.label
            ?: harnessId
        return WorkspaceCreationConfirmation(
            message = "Folder not found for $label. Would you like to create it?",
            path = path
        )
    }

    fun requireHomePrefix(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed == "~") return "~/"
        val displayed = display(trimmed)
        return when {
            displayed == "~" -> "~/"
            displayed.startsWith("~/") -> displayed
            displayed.startsWith("~") -> "~/${displayed.drop(1).trimStart('/')}"
            else -> "~/${displayed.trimStart('/')}"
        }
    }
}
