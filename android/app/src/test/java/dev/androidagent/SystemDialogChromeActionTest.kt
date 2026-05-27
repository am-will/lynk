package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemDialogChromeActionTest {
    @Test
    fun homeSystemDialogOnlyMinimizesPanel() {
        assertEquals(SystemDialogChromeAction.MinimizePanel, systemDialogChromeAction("homekey"))
    }

    @Test
    fun recentsSystemDialogTemporarilySuppressesAgentChrome() {
        assertEquals(SystemDialogChromeAction.SuppressAgentChrome, systemDialogChromeAction("recentapps"))
    }

    @Test
    fun unknownSystemDialogDoesNotChangeChrome() {
        assertEquals(SystemDialogChromeAction.None, systemDialogChromeAction(null))
        assertEquals(SystemDialogChromeAction.None, systemDialogChromeAction("assist"))
    }

    @Test
    fun recentsSurfaceDetectionMatchesCommonOverviewClasses() {
        assertEquals(true, isSystemRecentsSurface("com.android.systemui", "com.android.systemui.recents.RecentsActivity"))
        assertEquals(true, isSystemRecentsSurface("com.google.android.apps.nexuslauncher", "com.android.quickstep.RecentsActivity"))
        assertEquals(false, isSystemRecentsSurface("app.lynk", "dev.androidagent.MainActivity"))
        assertEquals(false, isSystemRecentsSurface("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity"))
    }
}
