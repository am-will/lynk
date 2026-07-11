package dev.androidagent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeTaskAdmissionTest {
    @Test
    fun duplicateIdsAreIgnoredAndQueueOverflowIsRejected() {
        val admission = RealtimeTaskAdmission(maxQueued = 2, maxTrackedCallIds = 8)
        assertEquals(RealtimeTaskAdmission.Result.ACCEPTED, admission.admit("active", false, 0))
        assertEquals(RealtimeTaskAdmission.Result.DUPLICATE, admission.admit("active", true, 0))
        assertEquals(RealtimeTaskAdmission.Result.ACCEPTED, admission.admit("queued-1", true, 0))
        assertEquals(RealtimeTaskAdmission.Result.ACCEPTED, admission.admit("queued-2", true, 1))
        assertEquals(RealtimeTaskAdmission.Result.QUEUE_FULL, admission.admit("overflow", true, 2))
        assertEquals(RealtimeTaskAdmission.Result.DUPLICATE, admission.admit("overflow", true, 0))
    }

    @Test
    fun ledgerMemoryIsBounded() {
        val admission = RealtimeTaskAdmission(maxQueued = 1, maxTrackedCallIds = 2)
        admission.admit("one", false, 0)
        admission.admit("two", false, 0)
        admission.admit("three", false, 0)
        assertEquals(RealtimeTaskAdmission.Result.ACCEPTED, admission.admit("one", false, 0))
    }
}
