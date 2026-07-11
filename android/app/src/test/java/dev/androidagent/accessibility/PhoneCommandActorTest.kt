package dev.androidagent.accessibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandActorTest {
    @Test
    fun commandsRunInFifoOrderWithoutOverlap() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var running = 0
        var maxRunning = 0
        val actor = PhoneCommandActor(Dispatchers.Default) { invocation ->
            synchronized(events) {
                running += 1
                maxRunning = maxOf(maxRunning, running)
                events += "start:${invocation.commandId}"
            }
            if (invocation.commandId == "one") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            synchronized(events) {
                events += "end:${invocation.commandId}"
                running -= 1
            }
            CommandResult(true, null)
        }

        val first = async { actor.execute(invocation("one", "owner")) }
        firstStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("two", "owner")) }
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)

        assertTrue(first.await().ok)
        assertTrue(second.await().ok)
        assertEquals(listOf("start:one", "end:one", "start:two", "end:two"), events)
        assertEquals(1, maxRunning)
        actor.close()
    }

    @Test
    fun queueBoundRejectsWithoutDisturbingAdmittedWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actor = PhoneCommandActor(Dispatchers.Default, queueCapacity = 1) { invocation ->
            if (invocation.commandId == "active") {
                started.complete(Unit)
                release.await()
            }
            CommandResult(true, null)
        }
        val active = async { actor.execute(invocation("active", "a")) }
        started.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("queued", "b")) }
        val rejected = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("rejected", "c")) }

        assertEquals(PhoneCommandActor.QUEUE_FULL, withTimeout(1_000) { rejected.await() }.error)
        release.complete(Unit)
        assertTrue(active.await().ok)
        assertTrue(queued.await().ok)
        actor.close()
    }

    @Test
    fun ownerCancellationStopsActiveAndQueuedButNotOtherOwners() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val actor = PhoneCommandActor(Dispatchers.Default) { invocation ->
            if (invocation.ownerId == "cancel-me") {
                started.complete(Unit)
                awaitCancellation()
            }
            CommandResult(true, null)
        }
        val active = async { actor.execute(invocation("active", "cancel-me")) }
        started.await()
        val queuedSameOwner = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("queued-a", "cancel-me")) }
        val otherOwner = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("queued-b", "keep-me")) }

        actor.cancelOwner("cancel-me")

        assertEquals(PhoneCommandActor.OWNER_CANCELLED, active.await().error)
        assertEquals(PhoneCommandActor.OWNER_CANCELLED, queuedSameOwner.await().error)
        assertTrue(otherOwner.await().ok)
        actor.close()
    }

    @Test
    fun closeSettlesActiveQueuedAndFutureCommands() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val actor = PhoneCommandActor(Dispatchers.Default) {
            started.complete(Unit)
            awaitCancellation()
        }
        val active = async { actor.execute(invocation("active", "a")) }
        started.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { actor.execute(invocation("queued", "b")) }

        actor.close()

        assertEquals(PhoneCommandActor.SERVICE_CLOSED, active.await().error)
        assertEquals(PhoneCommandActor.SERVICE_CLOSED, queued.await().error)
        assertEquals(PhoneCommandActor.SERVICE_CLOSED, actor.execute(invocation("future", "c")).error)
    }

    private fun invocation(id: String, owner: String) = PhoneCommandInvocation(
        commandId = id,
        ownerId = owner,
        command = "wait",
        args = JSONObject(),
        approvalCapability = null
    )
}
