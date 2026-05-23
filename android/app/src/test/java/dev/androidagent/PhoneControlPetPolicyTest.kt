package dev.androidagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlPetPolicyTest {
    @Test
    fun disabledPetUsesRuntimeOverrideOnly() {
        val policy = PhoneControlPetPolicy()

        policy.activate(userPetEnabled = false)

        assertTrue(policy.overrideVisible)
        assertTrue(policy.restoreOverrideIfNeeded(userPetEnabled = false))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun enabledPetDoesNotNeedRuntimeOverride() {
        val policy = PhoneControlPetPolicy()

        policy.activate(userPetEnabled = true)

        assertFalse(policy.overrideVisible)
        assertFalse(policy.restoreOverrideIfNeeded(userPetEnabled = true))
    }

    @Test
    fun completionProtectsUnreadUntilAcknowledged() {
        val policy = PhoneControlPetPolicy()

        policy.holdAfterCompletion(
            userPetEnabled = false,
            sessionKey = "agent:main:phone",
            runId = "run_phone"
        )

        assertTrue(policy.overrideVisible)
        assertEquals("agent:main:phone", policy.attentionSessionKey)
        assertEquals("run_phone", policy.attentionRunId)
        assertTrue(policy.shouldPreserveUnread("agent:main:phone"))
        assertTrue(policy.acknowledgeReply("agent:main:phone", userPetEnabled = false))
        assertFalse(policy.shouldPreserveUnread("agent:main:phone"))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun timedClearReleasesProtectionAndHidesTransientPet() {
        val policy = PhoneControlPetPolicy()
        policy.holdAfterCompletion(
            userPetEnabled = false,
            sessionKey = "agent:main:phone",
            runId = "run_phone"
        )

        assertTrue(policy.clearTimedAttention(userPetEnabled = false))

        assertFalse(policy.shouldPreserveUnread("agent:main:phone"))
        assertFalse(policy.overrideVisible)
    }

    @Test
    fun rememberedRunsTrackPhoneControlTerminals() {
        val policy = PhoneControlPetPolicy()

        policy.rememberRun(sessionKey = "agent:main:phone", runId = "run_phone")

        assertTrue(policy.isRememberedRun("run_phone"))
        policy.forgetRun("run_phone")
        assertFalse(policy.isRememberedRun("run_phone"))
    }
}
