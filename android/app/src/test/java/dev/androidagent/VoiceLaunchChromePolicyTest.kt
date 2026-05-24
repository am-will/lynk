package dev.androidagent

import dev.androidagent.overlay.PanelPresentation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLaunchChromePolicyTest {
    @Test
    fun popupVoiceLaunchKeepsHostAppInPlace() {
        assertFalse(shouldMinimizeHostAppAfterVoiceStart(PanelPresentation.Popup))
    }

    @Test
    fun fullscreenVoiceLaunchMinimizesHostApp() {
        assertTrue(shouldMinimizeHostAppAfterVoiceStart(PanelPresentation.Fullscreen))
    }

    @Test
    fun shellVoiceLaunchMinimizesHostApp() {
        assertTrue(shouldMinimizeHostAppAfterVoiceStart(PanelPresentation.Shell))
    }
}
