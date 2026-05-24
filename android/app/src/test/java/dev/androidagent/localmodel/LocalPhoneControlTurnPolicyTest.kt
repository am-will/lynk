package dev.androidagent.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPhoneControlTurnPolicyTest {
    @Test
    fun retriesInitialPhonePlanWithoutToolCall() {
        assertTrue(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "I'm going to open Settings, tap Apps, and check the permission.",
            phoneToolExecuted = false,
            phoneActionCount = 0,
            multiStepRequest = true
        ))
    }

    @Test
    fun retriesFollowUpPlanAfterToolResult() {
        assertTrue(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "Next, I'll tap the Notifications row.",
            phoneToolExecuted = true,
            phoneActionCount = 1,
            multiStepRequest = true
        ))
    }

    @Test
    fun retriesPrematureCompletionAfterOnlyOneMultiStepAction() {
        assertTrue(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "TASK_COMPLETE: Done.",
            phoneToolExecuted = true,
            phoneActionCount = 1,
            multiStepRequest = true
        ))
    }

    @Test
    fun retriesTerminalResponseThatStillDescribesRemainingWork() {
        assertTrue(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "TASK_COMPLETE: I can see the list of conversations. I need to find My Bride and tap it.",
            phoneToolExecuted = true,
            phoneActionCount = 2,
            multiStepRequest = true
        ))
    }

    @Test
    fun allowsVerifiedOrBlockedTerminalResponses() {
        assertFalse(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "TASK_COMPLETE: Settings is open and the requested toggle is enabled.",
            phoneToolExecuted = true,
            phoneActionCount = 2,
            multiStepRequest = true
        ))
        assertFalse(LocalPhoneControlTurnPolicy.shouldRetryNoToolResponse(
            "BLOCKED: The current screen is a password prompt; please unlock manually.",
            phoneToolExecuted = false,
            phoneActionCount = 0,
            multiStepRequest = true
        ))
    }

    @Test
    fun detectsCommonMultiStepPhoneRequests() {
        assertTrue(LocalPhoneControlTurnPolicy.isMultiStepRequest("Open YouTube, then search for lo-fi."))
        assertTrue(LocalPhoneControlTurnPolicy.isMultiStepRequest("Open Settings and tap Apps."))
    }
}
