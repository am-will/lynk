package dev.androidagent

private const val SYSTEM_DIALOG_REASON_HOME_KEY = "homekey"
private const val SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps"

internal enum class SystemDialogChromeAction {
    None,
    MinimizePanel,
    SuppressAgentChrome
}

internal fun systemDialogChromeAction(reason: String?): SystemDialogChromeAction {
    return when (reason) {
        SYSTEM_DIALOG_REASON_HOME_KEY -> SystemDialogChromeAction.MinimizePanel
        SYSTEM_DIALOG_REASON_RECENT_APPS -> SystemDialogChromeAction.SuppressAgentChrome
        else -> SystemDialogChromeAction.None
    }
}

internal fun isSystemRecentsSurface(packageName: String?, className: String?): Boolean {
    val packageValue = packageName.orEmpty().lowercase()
    val classValue = className.orEmpty().lowercase()
    val combined = "$packageValue $classValue"
    return combined.contains("recents") ||
        combined.contains("overview") ||
        (packageValue.contains("launcher") && classValue.contains("quickstep")) ||
        (packageValue == "com.android.systemui" && classValue.contains("recents"))
}
