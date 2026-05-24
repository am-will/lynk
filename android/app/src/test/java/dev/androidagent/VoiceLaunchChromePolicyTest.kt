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

    @Test
    fun disabledPetRealtimeVoiceUsesRuntimeOverrideUntilTimer() {
        val policy = PhoneControlAttentionReducer()

        assertTrue(PhoneControlAttentionEffect.ShowTransientPet in policy.activate(userPetEnabled = false))
        assertTrue(policy.overrideVisible)

        policy.holdAfterCompletion(userPetEnabled = false, sessionKey = null, runId = null)

        assertTrue(PhoneControlAttentionEffect.HideTransientPet in policy.clearTimedAttention(userPetEnabled = false))
        assertFalse(policy.overrideVisible)
    }
}
