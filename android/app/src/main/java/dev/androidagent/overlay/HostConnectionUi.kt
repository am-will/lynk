package dev.androidagent.overlay

import dev.androidagent.ui.ThemeTokens

object HostConnectionCopy {
    fun title(phase: HostConnectionPhase): String = when (phase) {
        HostConnectionPhase.CONNECTING -> "Connecting to Host"
        HostConnectionPhase.CONNECTED -> "Host Connected"
        HostConnectionPhase.ERROR -> "Host Connection Error"
    }

    fun message(state: HostConnectionState): String {
        return state.message.takeIf { it.isNotBlank() } ?: when (state.phase) {
            HostConnectionPhase.CONNECTING -> "Trying to reach the OpenClaw bridge on the host machine."
            HostConnectionPhase.CONNECTED -> "The Android app is registered with the OpenClaw bridge."
            HostConnectionPhase.ERROR -> "The Android app could not reach or register with the OpenClaw bridge."
        }
    }
}

fun hostConnectionColor(tokens: ThemeTokens, phase: HostConnectionPhase): Int = when (phase) {
    HostConnectionPhase.CONNECTING -> tokens.warning
    HostConnectionPhase.CONNECTED -> tokens.success
    HostConnectionPhase.ERROR -> tokens.danger
}
