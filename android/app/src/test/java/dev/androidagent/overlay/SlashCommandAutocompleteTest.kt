package dev.androidagent.overlay

import dev.androidagent.chat.ChatCommandOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandAutocompleteTest {
    @Test
    fun currentTokenFindsSlashCommandAtCursor() {
        assertEquals(
            SlashToken(start = 0, end = 4, query = "sta"),
            SlashCommandAutocomplete.currentToken("/sta", 4)
        )
        assertEquals(
            SlashToken(start = 6, end = 10, query = "que"),
            SlashCommandAutocomplete.currentToken("hello /que", 10)
        )
    }

    @Test
    fun currentTokenIgnoresNonSlashAndArgumentText() {
        assertNull(SlashCommandAutocomplete.currentToken("hello", 5))
        assertNull(SlashCommandAutocomplete.currentToken("/queue steer", 12))
        assertNull(SlashCommandAutocomplete.currentToken("hello /queue steer", 18))
    }

    @Test
    fun matchingCommandsMatchesNamesAndAliases() {
        val commands = listOf(
            command("status", aliases = listOf("/stat")),
            command("queue", aliases = listOf("/q")),
            command("reasoning", aliases = listOf("/think"))
        )

        assertEquals(listOf("status"), SlashCommandAutocomplete.matchingCommands(commands, "sta").map { it.name })
        assertEquals(listOf("queue"), SlashCommandAutocomplete.matchingCommands(commands, "q").map { it.name })
        assertEquals(listOf("reasoning"), SlashCommandAutocomplete.matchingCommands(commands, "thi").map { it.name })
        assertEquals(listOf("status", "queue"), SlashCommandAutocomplete.matchingCommands(commands, "", limit = 2).map { it.name })
    }

    @Test
    fun commandTextUsesFirstAliasOnlyWhenItIsSlashPrefixed() {
        assertEquals("/stat", SlashCommandAutocomplete.commandText(command("status", aliases = listOf("/stat", "stat"))))
        assertEquals("/status", SlashCommandAutocomplete.commandText(command("status", aliases = listOf("stat"))))
    }

    @Test
    fun applyAutocompleteReplacesOnlyCurrentToken() {
        val result = SlashCommandAutocomplete.applyAutocomplete(
            text = "hello /sta",
            token = SlashToken(start = 6, end = 10, query = "sta"),
            commandText = "/status"
        )

        assertEquals("hello /status ", result.text)
        assertEquals("hello /status ".length, result.cursor)
    }

    private fun command(name: String, aliases: List<String> = emptyList()): ChatCommandOption {
        return ChatCommandOption(
            name = name,
            description = null,
            category = null,
            aliases = aliases,
            acceptsArgs = false
        )
    }
}
