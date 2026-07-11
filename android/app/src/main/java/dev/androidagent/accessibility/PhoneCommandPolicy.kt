package dev.androidagent.accessibility

internal enum class PhoneCommandRisk {
    Observe,
    Navigation,
    Sensitive,
    Approval
}

internal object PhoneCommandPolicy {
    val risks: Map<String, PhoneCommandRisk> = mapOf(
        "observe_screen" to PhoneCommandRisk.Observe,
        "open_app" to PhoneCommandRisk.Navigation,
        "tap_node" to PhoneCommandRisk.Sensitive,
        "tap_xy" to PhoneCommandRisk.Sensitive,
        "tap_normalized" to PhoneCommandRisk.Sensitive,
        "long_press_node" to PhoneCommandRisk.Sensitive,
        "type_text" to PhoneCommandRisk.Sensitive,
        "submit_text" to PhoneCommandRisk.Sensitive,
        "scroll" to PhoneCommandRisk.Navigation,
        "swipe" to PhoneCommandRisk.Navigation,
        "press_back" to PhoneCommandRisk.Navigation,
        "press_home" to PhoneCommandRisk.Navigation,
        "open_recents" to PhoneCommandRisk.Navigation,
        "take_screenshot" to PhoneCommandRisk.Sensitive,
        "ask_user_confirmation" to PhoneCommandRisk.Approval,
        "wait" to PhoneCommandRisk.Observe
    )

    fun risk(command: String): PhoneCommandRisk? = risks[command]

    fun requiresApproval(command: String): Boolean = risk(command) == PhoneCommandRisk.Sensitive
}
