package dev.androidagent.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCapabilityStoreTest {
    private var now = 1_000L
    private var tokenIndex = 0
    private val store = ApprovalCapabilityStore({ now }, { "token-${++tokenIndex}" }, ttlMs = 100L)

    @Test
    fun descriptorDigestIsStableAcrossArgumentOrder() {
        val first = PhoneActionDescriptor.create("tap_xy", JSONObject().put("x", 1).put("y", 2))
        val second = PhoneActionDescriptor.create("tap_xy", JSONObject().put("y", 2).put("x", 1))

        assertEquals(first.digest, second.digest)
        assertEquals("Tap screen coordinates (1, 2)", first.summary)
    }

    @Test
    fun capabilityIsSingleUseAndBoundToOwnerActionAndObservation() {
        val action = action("n1")
        val capability = store.issue("session-a", action, context("observation-1"))

        assertTrue(store.validateAndConsume(capability.token, "session-b", action, context("observation-1")) is ApprovalValidation.WrongOwner)
        assertTrue(store.validateAndConsume(capability.token, "session-a", action("n2"), context("observation-1")) is ApprovalValidation.WrongAction)
        val wrongCommand = PhoneActionDescriptor.create("type_text", JSONObject().put("text", "n1"))
        assertTrue(store.validateAndConsume(capability.token, "session-a", wrongCommand, context("observation-1")) is ApprovalValidation.WrongAction)
        assertTrue(store.validateAndConsume(capability.token, "session-a", action, context("observation-1")) is ApprovalValidation.Approved)
        assertEquals(ApprovalValidation.Replayed, store.validateAndConsume(capability.token, "session-a", action, context("observation-1")))
    }

    @Test
    fun missingCapabilityAndDeniedDecisionNeverAuthorize() {
        val action = action("n1")
        assertEquals(ApprovalValidation.Missing, store.validateAndConsume(null, "session-a", action, null))
        assertEquals(null, store.issueIfApproved(false, "session-a", action, null))
        assertEquals(ApprovalValidation.Unknown, store.validateAndConsume("never-issued", "session-a", action, null))
    }

    @Test
    fun changedObservationInvalidatesCapability() {
        val action = action("n1")
        val capability = store.issue("session-a", action, context("observation-1"))

        assertEquals(ApprovalValidation.ChangedObservation, store.validateAndConsume(capability.token, "session-a", action, context("observation-2")))
        assertEquals(ApprovalValidation.Cancelled, store.validateAndConsume(capability.token, "session-a", action, context("observation-1")))
    }

    @Test
    fun expiryAndOwnerCancellationHaveDistinctResults() {
        val expired = store.issue("session-a", action("n1"), null)
        now += 101
        assertEquals(ApprovalValidation.Expired, store.validateAndConsume(expired.token, "session-a", action("n1"), null))

        val cancelled = store.issue("session-a", action("n2"), null)
        store.cancelOwner("session-a")
        assertEquals(ApprovalValidation.Cancelled, store.validateAndConsume(cancelled.token, "session-a", action("n2"), null))
    }

    @Test
    fun everyDenialProducesStableMachineReadableReason() {
        val reasons = listOf(
            ApprovalValidation.Missing,
            ApprovalValidation.Unknown,
            ApprovalValidation.Expired,
            ApprovalValidation.Replayed,
            ApprovalValidation.Cancelled,
            ApprovalValidation.WrongOwner,
            ApprovalValidation.WrongAction,
            ApprovalValidation.ChangedObservation
        ).map { it.denialMessage("Tap observed node n1").orEmpty().substringBefore(':') }

        assertEquals(
            listOf(
                "authorization_required",
                "authorization_invalid",
                "authorization_expired",
                "authorization_replayed",
                "authorization_cancelled",
                "authorization_wrong_owner",
                "authorization_wrong_action",
                "authorization_context_changed"
            ),
            reasons
        )
    }

    private fun action(node: String) = PhoneActionDescriptor.create("tap_node", JSONObject().put("nodeId", node))
    private fun context(id: String) = ApprovalContext(id, "com.example", "MainActivity", 7)
}
