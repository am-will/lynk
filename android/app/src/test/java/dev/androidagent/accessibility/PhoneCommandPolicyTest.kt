package dev.androidagent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandPolicyTest {
    @Test
    fun observationAndNavigationRemainAvailableWithoutApproval() {
        listOf("observe_screen", "wait", "open_app", "scroll", "swipe", "press_back", "press_home", "open_recents")
            .forEach { assertFalse("$it should be non-sensitive", PhoneCommandPolicy.requiresApproval(it)) }
    }

    @Test
    fun directInteractionAndScreenCaptureRequireApproval() {
        listOf("tap_node", "tap_xy", "tap_normalized", "long_press_node", "type_text", "submit_text", "take_screenshot")
            .forEach { assertTrue("$it should be sensitive", PhoneCommandPolicy.requiresApproval(it)) }
    }

    @Test
    fun everyProtocolCommandHasExactlyOneRisk() {
        assertEquals(16, PhoneCommandPolicy.risks.size)
        assertEquals(PhoneCommandRisk.Approval, PhoneCommandPolicy.risk("ask_user_confirmation"))
        assertEquals(null, PhoneCommandPolicy.risk("unknown"))
    }
}
