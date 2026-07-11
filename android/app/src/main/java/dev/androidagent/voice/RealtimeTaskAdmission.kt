package dev.androidagent.voice

internal class RealtimeTaskAdmission(
    private val maxQueued: Int,
    private val maxTrackedCallIds: Int
) {
    enum class Result { ACCEPTED, DUPLICATE, QUEUE_FULL }

    private val callIds = LinkedHashSet<String>()

    fun admit(callId: String, hasActiveTask: Boolean, queuedCount: Int): Result {
        if (callId in callIds) return Result.DUPLICATE
        remember(callId)
        return if (hasActiveTask && queuedCount >= maxQueued) Result.QUEUE_FULL else Result.ACCEPTED
    }

    private fun remember(callId: String) {
        callIds += callId
        while (callIds.size > maxTrackedCallIds) callIds.remove(callIds.first())
    }
}
