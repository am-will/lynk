package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TermuxExecutionProtocolTest {
    private val identity = TermuxExecutionIdentity(
        executionId = "0123456789abcdef0123456789abcdef",
        nonce = "fedcba9876543210fedcba9876543210"
    )

    @Test
    fun userCommandIsAnOpaqueArgumentAndNeverInterpolatedIntoWrapperSource() {
        val adversarial = "printf '%s' \"\$(touch /tmp/should-not-run)\"; echo ' spaced value '"

        val request = TermuxExecutionProtocol.wrappedCommand(identity, adversarial, "/tmp/work dir")

        assertEquals(TermuxExecutionProtocol.TERMUX_BASH, request.commandPath)
        assertEquals("-c", request.arguments[0])
        assertEquals(identity.executionId, request.arguments[3])
        assertEquals(identity.nonce, request.arguments[4])
        assertEquals(adversarial, request.arguments[5])
        assertFalse(request.arguments[1].contains(adversarial))
        assertEquals("/tmp/work dir", request.workdir)
    }

    @Test
    fun controlArgumentsAreNonceBoundAndNotShellInterpolated() {
        val start = TermuxExecutionProtocol.startControl(identity)
        val cancel = TermuxExecutionProtocol.cancelControl(identity)

        assertEquals(listOf("lynk-run-control", "start", identity.executionId, identity.nonce), start.arguments.drop(2))
        assertEquals(listOf("lynk-run-control", "cancel", identity.executionId, identity.nonce), cancel.arguments.drop(2))
        assertFalse(start.arguments[1].contains(identity.executionId))
        assertFalse(cancel.arguments[1].contains(identity.nonce))
    }

    @Test
    fun wrapperAndControlScriptsHaveValidBashSyntaxAndNoUnexpandedMarkers() {
        val wrapper = TermuxExecutionProtocol.wrappedCommand(identity, "true", "").arguments[1]
        val control = TermuxExecutionProtocol.cancelControl(identity).arguments[1]

        assertFalse(wrapper.contains("__D__"))
        assertFalse(control.contains("__D__"))
        assertBashSyntax(wrapper)
        assertBashSyntax(control)
    }

    @Test
    fun controlScriptGuardsPidReuseBeforeSignallingTheProcessGroup() {
        val control = TermuxExecutionProtocol.cancelControl(identity).arguments[1]

        assertTrue(control.contains("/proc/\$1/stat"))
        assertTrue(control.contains("current_start"))
        assertTrue(control.contains("current_pgid"))
        assertTrue(control.contains("kill -KILL -- \"-\$pgid\""))
    }

    @Test
    fun parsesVerifiedStartAndKillMarkersStrictly() {
        val start = TermuxExecutionProtocol.parseControlResult(
            "LYNK_START verified ${identity.executionId} 123 123 9001\n"
        )
        val kill = TermuxExecutionProtocol.parseControlResult(
            "noise\nLYNK_KILL verified ${identity.executionId} 123 123 9001\n"
        )

        assertNotNull(start)
        assertEquals("start", start?.operation)
        assertEquals(TermuxProcessIdentity(123, 123, 9001, ""), start?.process)
        assertTrue(kill?.verified == true)
        assertEquals("kill", kill?.operation)
        assertEquals(null, TermuxExecutionProtocol.parseControlResult("LYNK_KILL verified bad-id 1 1 1"))
    }

    @Test
    fun rejectsIdentifiersThatCouldEscapeCoordinationPaths() {
        try {
            TermuxExecutionIdentity("../../tmp/escape", identity.nonce)
            fail("Expected unsafe execution id to be rejected")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun assertBashSyntax(script: String) {
        val process = ProcessBuilder("/bin/bash", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(stderr, 0, process.waitFor())
    }
}
