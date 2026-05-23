package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlPetPolicyTest {
    @Test
    fun disabledPetUsesRuntimeOverrideOnly() {
        val policy = PhoneControlAttentionReducer()

        val showEffects = policy.dispatch(PhoneControlAttentionEvent.RunStarted("agent:main:phone", "run_phone"), userPetEnabled = false)

        assertTrue(policy.overrideVisible)
        assertTrue(PhoneControlAttentionEffect.ShowTransientPet in showEffects)
        assertTrue(PhoneControlAttentionEffect.HideTransientPet in policy.restoreOverrideIfNeeded(userPetEnabled = false))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun enabledPetDoesNotNeedRuntimeOverride() {
        val policy = PhoneControlAttentionReducer()

        policy.activate(userPetEnabled = true)

        assertFalse(policy.overrideVisible)
        assertFalse(PhoneControlAttentionEffect.HideTransientPet in policy.restoreOverrideIfNeeded(userPetEnabled = true))
    }

    @Test
    fun completionProtectsUnreadUntilAcknowledged() {
        val policy = PhoneControlAttentionReducer()

        val effects = policy.holdAfterCompletion(
            userPetEnabled = false,
            sessionKey = "agent:main:phone",
            runId = "run_phone"
        )

        assertTrue(policy.overrideVisible)
        assertEquals("agent:main:phone", policy.attentionSessionKey)
        assertEquals("run_phone", policy.attentionRunId)
        assertTrue(policy.shouldPreserveUnread("agent:main:phone"))
        assertTrue(PhoneControlAttentionEffect.ShowTransientPet in effects)
        assertTrue(PhoneControlAttentionEffect.HideTransientPet in policy.acknowledgeReply("agent:main:phone", userPetEnabled = false))
        assertFalse(policy.shouldPreserveUnread("agent:main:phone"))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun timedClearReleasesProtectionAndHidesTransientPet() {
        val policy = PhoneControlAttentionReducer()
        policy.holdAfterCompletion(
            userPetEnabled = false,
            sessionKey = "agent:main:phone",
            runId = "run_phone"
        )

        assertTrue(PhoneControlAttentionEffect.HideTransientPet in policy.dispatch(PhoneControlAttentionEvent.TimerExpired, userPetEnabled = false))

        assertFalse(policy.shouldPreserveUnread("agent:main:phone"))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun rememberedRunsTrackPhoneControlTerminals() {
        val policy = PhoneControlAttentionReducer()

        policy.rememberRun(sessionKey = "agent:main:phone", runId = "run_phone")

        assertTrue(policy.isRememberedRun("run_phone"))
        policy.forgetRun("run_phone")
        assertFalse(policy.isRememberedRun("run_phone"))
    }
}
