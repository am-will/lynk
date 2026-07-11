package dev.androidagent.accessibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.coroutines.resume

internal data class PhoneCommandInvocation(
    val commandId: String,
    val ownerId: String,
    val command: String,
    val args: JSONObject,
    val approvalCapability: String?
)

/** One lifecycle-owned FIFO for every accessibility command. */
internal class PhoneCommandActor(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val runner: suspend (PhoneCommandInvocation) -> CommandResult
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val queued = ArrayDeque<Entry>()
    private var active: Active? = null
    private var closed = false
    private val worker = scope.launch { runQueue() }

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    suspend fun execute(invocation: PhoneCommandInvocation): CommandResult =
        suspendCancellableCoroutine { continuation ->
            val entry = Entry(
                invocation.copy(args = JSONObject(invocation.args.toString())),
                complete = { result -> if (continuation.isActive) continuation.resume(result) }
            )
            continuation.invokeOnCancellation {
                cancelCommand(invocation.commandId, invocation.ownerId, COMMAND_CANCELLED)
            }
            val rejection = synchronized(lock) {
                when {
                    closed -> SERVICE_CLOSED
                    queued.size >= queueCapacity -> QUEUE_FULL
                    else -> {
                        queued.addLast(entry)
                        null
                    }
                }
            }
            if (rejection != null) {
                entry.complete(CommandResult(false, null, rejection))
            } else {
                wakeup.trySend(Unit)
            }
        }

    fun cancelOwner(ownerId: String, reason: String = OWNER_CANCELLED) {
        cancelMatching({ it.ownerId == ownerId }, reason)
    }

    fun cancelOwnerPrefix(prefix: String, reason: String = OWNER_CANCELLED) {
        cancelMatching({ it.ownerId.startsWith(prefix) }, reason)
    }

    fun cancelCommand(commandId: String, ownerId: String? = null, reason: String = COMMAND_CANCELLED) {
        cancelMatching({ it.commandId == commandId && (ownerId == null || it.ownerId == ownerId) }, reason)
    }

    fun close(reason: String = SERVICE_CLOSED) {
        val queuedToCancel: List<Entry>
        val activeToCancel: Active?
        synchronized(lock) {
            if (closed) return
            closed = true
            queuedToCancel = queued.toList()
            queued.clear()
            activeToCancel = active
            activeToCancel?.entry?.cancelError = reason
        }
        queuedToCancel.forEach { it.complete(CommandResult(false, null, reason)) }
        activeToCancel?.job?.cancel(CancellationException(reason))
        wakeup.trySend(Unit)
    }

    private suspend fun runQueue() {
        while (true) {
            wakeup.receive()
            while (true) {
                val entry = synchronized(lock) {
                    queued.removeFirstOrNull()?.also { active = Active(it, null) }
                } ?: break
                val job = scope.launch {
                    val result = try {
                        entry.cancelError?.let { return@launch entry.complete(CommandResult(false, null, it)) }
                        runner(entry.invocation)
                    } catch (error: CancellationException) {
                        CommandResult(false, null, entry.cancelError ?: COMMAND_CANCELLED)
                    } catch (error: Throwable) {
                        CommandResult(false, null, error.message ?: error.toString())
                    }
                    entry.complete(result)
                }
                synchronized(lock) {
                    active?.takeIf { it.entry === entry }?.job = job
                    if (entry.cancelError != null) job.cancel(CancellationException(entry.cancelError))
                }
                job.join()
                synchronized(lock) {
                    if (active?.entry === entry) active = null
                }
                yield()
            }
            if (synchronized(lock) { closed && queued.isEmpty() && active == null }) break
        }
        worker.cancel()
    }

    private fun cancelMatching(predicate: (PhoneCommandInvocation) -> Boolean, reason: String) {
        val queuedToCancel = mutableListOf<Entry>()
        var activeToCancel: Active? = null
        synchronized(lock) {
            val iterator = queued.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (predicate(entry.invocation)) {
                    iterator.remove()
                    entry.cancelError = reason
                    queuedToCancel += entry
                }
            }
            active?.takeIf { predicate(it.entry.invocation) }?.let {
                it.entry.cancelError = reason
                activeToCancel = it
            }
        }
        queuedToCancel.forEach { it.complete(CommandResult(false, null, reason)) }
        activeToCancel?.job?.cancel(CancellationException(reason))
    }

    private class Entry(
        val invocation: PhoneCommandInvocation,
        val complete: (CommandResult) -> Unit
    ) {
        @Volatile var cancelError: String? = null
    }

    private data class Active(val entry: Entry, var job: Job?)

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 32
        const val QUEUE_FULL = "command_queue_full: too many phone commands are waiting"
        const val COMMAND_CANCELLED = "command_cancelled: phone command was cancelled"
        const val OWNER_CANCELLED = "command_owner_cancelled: phone command owner stopped"
        const val SERVICE_CLOSED = "command_service_closed: phone command executor closed"
    }
}

private fun <T> ArrayDeque<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeFirst()
