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
    fun currentSkillTokenFindsDollarSkillAtCursor() {
        assertEquals(
            SlashToken(start = 0, end = 3, query = "co"),
            SlashCommandAutocomplete.currentSkillToken("\$co", 3)
        )
        assertEquals(
            SlashToken(start = 6, end = 13, query = "commit"),
            SlashCommandAutocomplete.currentSkillToken("use a \$commit", 13)
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
            command("reasoning", aliases = listOf("/think")),
            command("commit-message", isSkill = true)
        )

        assertEquals(listOf("status"), SlashCommandAutocomplete.matchingCommands(commands, "sta").map { it.name })
        assertEquals(listOf("queue"), SlashCommandAutocomplete.matchingCommands(commands, "q").map { it.name })
        assertEquals(listOf("reasoning"), SlashCommandAutocomplete.matchingCommands(commands, "thi").map { it.name })
        assertEquals(listOf("status", "queue"), SlashCommandAutocomplete.matchingCommands(commands, "", limit = 2).map { it.name })
    }

    @Test
    fun matchingSkillsMatchesOnlySkillNames() {
        val commands = listOf(
            command("commands"),
            command("commit-message", isSkill = true),
            command("code-review", isSkill = true),
            command("summarize", isSkill = true)
        )

        assertEquals(
            listOf("commit-message", "code-review"),
            SlashCommandAutocomplete.matchingSkills(commands, "co").map { it.name }
        )
        assertEquals(listOf("commit-message"), SlashCommandAutocomplete.matchingSkills(commands, "com").map { it.name })
    }

    @Test
    fun commandTextUsesFirstAliasOnlyWhenItIsSlashPrefixed() {
        assertEquals("/stat", SlashCommandAutocomplete.commandText(command("status", aliases = listOf("/stat", "stat"))))
        assertEquals("/status", SlashCommandAutocomplete.commandText(command("status", aliases = listOf("stat"))))
    }

    @Test
    fun skillTextUsesDollarPrefix() {
        assertEquals("\$commit-message", SlashCommandAutocomplete.skillText(command("commit-message", isSkill = true)))
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

    @Test
    fun applyAutocompleteReplacesDollarSkillToken() {
        val result = SlashCommandAutocomplete.applyAutocomplete(
            text = "use \$co for this",
            token = SlashToken(start = 4, end = 7, query = "co"),
            commandText = "\$code-review"
        )

        assertEquals("use \$code-review  for this", result.text)
        assertEquals("use \$code-review ".length, result.cursor)
    }

    private fun command(name: String, aliases: List<String> = emptyList(), isSkill: Boolean = false): ChatCommandOption {
        return ChatCommandOption(
            name = name,
            description = null,
            category = null,
            aliases = aliases,
            acceptsArgs = false,
            source = if (isSkill) "skill" else null
        )
    }
}
