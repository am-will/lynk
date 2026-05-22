package dev.androidagent.overlay

import dev.androidagent.chat.ChatCommandOption

data class SlashToken(val start: Int, val end: Int, val query: String)

data class SlashAutocompleteResult(val text: String, val cursor: Int)

object SlashCommandAutocomplete {
    fun currentToken(text: String, cursorIndex: Int): SlashToken? {
        return currentToken(text, cursorIndex, '/')
    }

    fun currentSkillToken(text: String, cursorIndex: Int): SlashToken? {
        return currentToken(text, cursorIndex, '$')
    }

    private fun currentToken(text: String, cursorIndex: Int, trigger: Char): SlashToken? {
        val cursor = cursorIndex.coerceAtLeast(0).coerceAtMost(text.length)
        val start = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), (cursor - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        if (start >= text.length || text.getOrNull(start) != trigger) return null
        val end = cursor
        if (end < start + 1) return null
        val token = text.substring(start, end)
        if (token.drop(1).any { it.isWhitespace() }) return null
        return SlashToken(start = start, end = end, query = token.drop(1))
    }

    fun matchingCommands(
        commands: List<ChatCommandOption>,
        query: String,
        limit: Int = 20
    ): List<ChatCommandOption> {
        val normalized = query.trimStart('/').lowercase()
        return commands
            .filterNot { it.isSkill }
            .filter { command ->
                if (normalized.isBlank()) {
                    true
                } else {
                    command.name.lowercase().startsWith(normalized) ||
                        command.aliases.any { alias -> alias.trimStart('/').lowercase().startsWith(normalized) }
                }
            }
            .take(limit)
    }

    fun matchingSkills(
        commands: List<ChatCommandOption>,
        query: String,
        limit: Int = 20
    ): List<ChatCommandOption> {
        val normalized = query.trimStart('$').lowercase()
        return commands
            .filter { it.isSkill }
            .filter { skill ->
                normalized.isBlank() || skill.name.lowercase().startsWith(normalized)
            }
            .take(limit)
    }

    fun commandText(command: ChatCommandOption): String {
        return command.aliases.firstOrNull()?.takeIf { it.startsWith("/") } ?: "/${command.name}"
    }

    fun skillText(command: ChatCommandOption): String {
        return "\$${command.name}"
    }

    fun applyAutocomplete(text: String, token: SlashToken, commandText: String): SlashAutocompleteResult {
        val safeStart = token.start.coerceIn(0, text.length)
        val safeEnd = token.end.coerceIn(safeStart, text.length)
        val replacement = "$commandText "
        val next = text.replaceRange(safeStart, safeEnd, replacement)
        return SlashAutocompleteResult(
            text = next,
            cursor = (safeStart + replacement.length).coerceAtMost(next.length)
        )
    }
}
