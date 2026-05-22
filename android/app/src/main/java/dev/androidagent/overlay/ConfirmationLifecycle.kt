package dev.androidagent.overlay

import kotlinx.coroutines.CompletableDeferred

internal class ConfirmationLifecycle {
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    fun begin(): CompletableDeferred<Boolean> {
        completeActive(false)
        return CompletableDeferred<Boolean>().also { activeDeferred = it }
    }

    fun allow() {
        completeActive(true)
    }

    fun cancel() {
        completeActive(false)
    }

    fun dismiss() {
        completeActive(false)
    }

    private fun completeActive(value: Boolean) {
        val deferred = activeDeferred
        activeDeferred = null
        if (deferred?.isCompleted == false) {
            deferred.complete(value)
        }
    }
}
