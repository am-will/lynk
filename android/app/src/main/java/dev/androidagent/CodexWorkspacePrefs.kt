package dev.androidagent

object CodexWorkspacePaths {
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

    fun normalizeInput(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        return when {
            trimmed.isBlank() -> ""
            trimmed == "~" -> "~/"
            else -> display(trimmed)
        }
    }
}
