package dev.androidagent.localmodel

import dev.androidagent.localmodel.gguf.GgufCreateOperation
import dev.androidagent.localmodel.gguf.GgufCreateOperations
import dev.androidagent.localmodel.gguf.GgufModelKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufCancellationTest {

    @Test
    fun createOperationCancelsAndClosesNativeTokenExactlyOnce() {
        val native = FakeCreateOperations()
        val operation = GgufCreateOperation(native)
        val key = GgufModelKey("/models/model.gguf", 4096, "cpu", 0)

        assertEquals(42L, operation.create(key))
        operation.cancel()
        operation.cancel()
        operation.close()
        operation.close()

        assertEquals(1, native.beginCalls.get())
        assertEquals(1, native.createCalls.get())
        assertEquals(1, native.cancelCalls.get())
        assertEquals(1, native.closeCalls.get())
        assertEquals(91L, native.lastOperationHandle)
        assertEquals(key, native.lastKey)
    }

    @Test
    fun closedCreateOperationRejectsFurtherNativeWork() {
        val operation = GgufCreateOperation(FakeCreateOperations())
        operation.close()

        assertThrows(IllegalStateException::class.java) {
            operation.create(GgufModelKey("/models/model.gguf", 4096, "cpu", 0))
        }
    }

    @Test
    fun pendingHandleClosesOnceWhenCancellationWinsBeforeCacheHandoff() {
        val closed = mutableListOf<Long>()
        val pending = GgufPendingHandle(closed::add)
        pending.attach(42L)

        pending.cancel()
        pending.cancel()
        pending.discard()

        assertEquals(listOf(42L), closed)
        assertFalse(pending.transfer { error("cancelled handle must not reach cache") })
    }

    @Test
    fun pendingHandleTransfersOnceWithoutCancellationClosingCachedHandle() {
        val closed = mutableListOf<Long>()
        val accepted = mutableListOf<Long>()
        val pending = GgufPendingHandle(closed::add)
        pending.attach(42L)

        assertTrue(pending.transfer(accepted::add))
        pending.cancel()
        pending.discard()

        assertEquals(listOf(42L), accepted)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun handleCreatedAfterCancellationIsClosedInsteadOfAttached() {
        val closed = mutableListOf<Long>()
        val pending = GgufPendingHandle(closed::add)
        pending.cancel()

        pending.attach(42L)

        assertEquals(listOf(42L), closed)
        assertThrows(IllegalStateException::class.java) { pending.handleOrThrow() }
    }

    @Test
    fun nativeSignalFiresWhenCancellationStartsBeforeJobCompletes() = runBlocking {
        val enteredNonCancellableWork = CountDownLatch(1)
        val releaseNonCancellableWork = CountDownLatch(1)
        val nativeCancellation = CountDownLatch(1)
        val worker = launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                enteredNonCancellableWork.countDown()
                releaseNonCancellableWork.await()
            }
        }
        assertTrue(enteredNonCancellableWork.await(5, TimeUnit.SECONDS))
        val registration = worker.signalNativeOnCancellation {
            nativeCancellation.countDown()
        }

        try {
            worker.cancel()
            assertTrue(nativeCancellation.await(5, TimeUnit.SECONDS))
            assertFalse(worker.isCompleted)
        } finally {
            releaseNonCancellableWork.countDown()
            worker.cancelAndJoin()
            registration.dispose()
        }
    }

    @Test
    fun cancellationDuringDeltaCommitStopsRemainingBufferedDeltas() = runBlocking {
        val committed = mutableListOf<String>()
        val worker = launch {
            commitGgufDeltas(listOf("first", "second", "third")) { delta ->
                committed += delta
                currentCoroutineContext()[Job]?.cancel()
            }
        }

        worker.join()

        assertEquals(listOf("first"), committed)
        assertTrue(worker.isCancelled)
    }

    private class FakeCreateOperations : GgufCreateOperations {
        val beginCalls = AtomicInteger()
        val createCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        var lastOperationHandle: Long? = null
        var lastKey: GgufModelKey? = null

        override fun begin(): Long {
            beginCalls.incrementAndGet()
            return 91L
        }

        override fun create(
            operationHandle: Long,
            modelPath: String,
            contextTokens: Int,
            gpuLayers: Int,
            backendKey: String
        ): Long {
            createCalls.incrementAndGet()
            lastOperationHandle = operationHandle
            lastKey = GgufModelKey(modelPath, contextTokens, backendKey, gpuLayers)
            return 42L
        }

        override fun cancel(operationHandle: Long) {
            lastOperationHandle = operationHandle
            cancelCalls.incrementAndGet()
        }

        override fun close(operationHandle: Long) {
            lastOperationHandle = operationHandle
            closeCalls.incrementAndGet()
        }
    }
}
