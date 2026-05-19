package dev.androidagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class HostConnectionCopyTest {
    @Test
    fun titleReflectsPhase() {
        assertEquals("Connecting to Host", HostConnectionCopy.title(HostConnectionPhase.CONNECTING))
        assertEquals("Host Connected", HostConnectionCopy.title(HostConnectionPhase.CONNECTED))
        assertEquals("Host Connection Error", HostConnectionCopy.title(HostConnectionPhase.ERROR))
    }

    @Test
    fun messageUsesCustomCopyWhenPresent() {
        val state = HostConnectionState(HostConnectionPhase.ERROR, "Token rejected")

        assertEquals("Token rejected", HostConnectionCopy.message(state))
    }

    @Test
    fun messageFallsBackForBlankCustomCopy() {
        assertEquals(
            "The Android app is registered with the OpenClaw bridge.",
            HostConnectionCopy.message(HostConnectionState(HostConnectionPhase.CONNECTED, " "))
        )
    }
}
