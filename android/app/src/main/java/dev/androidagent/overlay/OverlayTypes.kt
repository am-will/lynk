package dev.androidagent.overlay

enum class PanelPresentation {
    Popup,
    Fullscreen,
    Shell
}

enum class HostConnectionPhase {
    CONNECTING,
    CONNECTED,
    ERROR
}

data class HostConnectionState(
    val phase: HostConnectionPhase,
    val message: String
)
