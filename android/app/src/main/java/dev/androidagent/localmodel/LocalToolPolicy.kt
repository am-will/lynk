package dev.androidagent.localmodel

internal data class LocalToolAccess(
    val phoneControl: Boolean = false,
    val workspaceRead: Boolean = false,
    val developer: Boolean = false
) {
    val allowsAny: Boolean
        get() = phoneControl || workspaceRead || developer

    fun allows(toolName: String): Boolean = when {
        toolName == "local_read_skill" -> phoneControl || workspaceRead || developer
        LocalToolSpecs.phoneCommandsByToolId.containsKey(toolName) -> phoneControl
        toolName in READ_ONLY_WORKSPACE_TOOLS -> workspaceRead || developer
        toolName in DEVELOPER_TOOLS -> developer
        else -> false
    }

    private companion object {
        val READ_ONLY_WORKSPACE_TOOLS = setOf("local_list_files", "local_read_file", "local_search_files")
        val DEVELOPER_TOOLS = setOf("local_write_file", "termux_command")
    }
}

/** Conservative intent admission. This does not infer authority from isolated keyword mentions. */
internal object LocalToolPolicy {
    private val phoneAction = Regex(
        """^(open|launch|tap|click|press|swipe|scroll|type|enter|send|take|capture|check|navigate|go|turn|enable|disable|set|show)\b"""
    )
    private val phoneTarget = Regex(
        """\b(phone|android|screen|screenshot|settings|notification|recents|home button|back button|app|camera|browser|youtube)\b"""
    )
    private val developerAction = Regex("""^(create|build|make|write|edit|save|run|execute|delete|move|copy)\b""")
    private val workspaceReadAction = Regex("""^(list|read|search|find|show)\b""")
    private val fileTarget = Regex("""\b(file|files|folder|directory|workspace|project|html|css|javascript|script|termux|terminal|shell)\b""")

    fun accessFor(userText: String): LocalToolAccess {
        if (CONTROL_MARKERS.any(userText::contains)) return LocalToolAccess()
        val command = normalizeRequest(userText)
        if (command.isBlank()) return LocalToolAccess()
        if (Regex("""^(explain|describe|define|what|why|how)\b""").containsMatchIn(command)) return LocalToolAccess()
        if (Regex("""^show\b.*\b(json|code|example)\b""").containsMatchIn(command)) return LocalToolAccess()
        val phone = phoneAction.containsMatchIn(command) && phoneTarget.containsMatchIn(command)
        val developer = developerAction.containsMatchIn(command) && fileTarget.containsMatchIn(command)
        val workspaceRead = workspaceReadAction.containsMatchIn(command) && fileTarget.containsMatchIn(command)
        return LocalToolAccess(phoneControl = phone, workspaceRead = workspaceRead, developer = developer)
    }

    fun isPhoneTool(name: String): Boolean = LocalToolSpecs.phoneCommandsByToolId.containsKey(name)

    private fun normalizeRequest(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("^(?:(?:can|could|would|will)\\s+you\\s+)?(?:please\\s+)?"), "")
        .replace(Regex("^(?:first|next|then)\\s*,?\\s+"), "")
        .replace(Regex("^on\\s+(?:my|the)\\s+(?:phone|android)\\s*,?\\s+"), "")
        .replace(Regex("^i\\s+(?:want|need)\\s+you\\s+to\\s+"), "")

    private val CONTROL_MARKERS = listOf("<|lynk_control|>", "<|/lynk_control|>")
}
