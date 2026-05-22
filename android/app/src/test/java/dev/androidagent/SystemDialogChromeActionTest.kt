package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemDialogChromeActionTest {
    @Test
    fun homeSystemDialogOnlyMinimizesPanel() {
        assertEquals(SystemDialogChromeAction.MinimizePanel, systemDialogChromeAction("homekey"))
    }

    @Test
    fun recentsSystemDialogHidesAgentChrome() {
        assertEquals(SystemDialogChromeAction.HideAgentChrome, systemDialogChromeAction("recentapps"))
    }

    @Test
    fun unknownSystemDialogDoesNotChangeChrome() {
        assertEquals(SystemDialogChromeAction.None, systemDialogChromeAction(null))
        assertEquals(SystemDialogChromeAction.None, systemDialogChromeAction("assist"))
    }
}
