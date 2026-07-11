package dev.androidagent.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolPolicyTest {
    @Test
    fun admitsExplicitImperativePhoneRequests() {
        val access = LocalToolPolicy.accessFor("Please open Android Settings and scroll down")
        assertTrue(access.phoneControl)
        assertTrue(access.allows("phone_open_app"))
        assertFalse(access.allows("termux_command"))
    }

    @Test
    fun admitsExplicitReadAndDeveloperRequestsSeparately() {
        val read = LocalToolPolicy.accessFor("Read the file notes.txt")
        assertTrue(read.workspaceRead)
        assertTrue(read.allows("local_read_file"))
        assertFalse(read.allows("local_write_file"))

        val developer = LocalToolPolicy.accessFor("Create an HTML project")
        assertTrue(developer.developer)
        assertTrue(developer.allows("local_write_file"))
        assertTrue(developer.allows("termux_command"))
        assertFalse(developer.allows("phone_observe"))
    }

    @Test
    fun explanationAndQuestionFormsDoNotGrantAuthority() {
        listOf(
            "Explain a shell command",
            "What is an HTML file?",
            "How do I open Android Settings?",
            "Show a JSON example of phone_tap_node"
        ).forEach { assertFalse(it, LocalToolPolicy.accessFor(it).allowsAny) }
    }
}
