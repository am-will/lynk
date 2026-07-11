package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalPhoneCommandOwnerTest {
    @Test
    fun ownerIsScopedToBothSessionAndRun() {
        assertEquals("local:session-a:run-1", LocalPhoneCommandOwner.id("session-a", "run-1"))
        assertNotEquals(
            LocalPhoneCommandOwner.id("session-a", "run-1"),
            LocalPhoneCommandOwner.id("session-a", "run-2")
        )
        assertNotEquals(
            LocalPhoneCommandOwner.id("session-a", "run-1"),
            LocalPhoneCommandOwner.id("session-b", "run-1")
        )
    }
}
