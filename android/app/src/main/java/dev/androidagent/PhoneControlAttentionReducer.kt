package dev.androidagent

internal sealed interface PhoneControlAttentionEvent {
    data class RunStarted(val sessionKey: String?, val runId: String?) : PhoneControlAttentionEvent
    data class RunCompleted(val sessionKey: String?, val runId: String?) : PhoneControlAttentionEvent
    data class ReplyViewed(val sessionKey: String?) : PhoneControlAttentionEvent
    data object TimerExpired : PhoneControlAttentionEvent
    data class RunForgotten(val runId: String?) : PhoneControlAttentionEvent
}

internal enum class PhoneControlAttentionEffect {
    ShowTransientPet,
    HideTransientPet
}

internal class PhoneControlAttentionReducer {
    private val runIds = mutableSetOf<String>()
    private val protectedReplySessions = mutableSetOf<String>()

    var overrideVisible: Boolean = false
        private set
    var attentionSessionKey: String? = null
        private set
    var attentionRunId: String? = null
        private set

    fun dispatch(
        event: PhoneControlAttentionEvent,
        userPetEnabled: Boolean
    ): Set<PhoneControlAttentionEffect> {
        return when (event) {
            is PhoneControlAttentionEvent.RunStarted -> {
                rememberRun(event.sessionKey, event.runId)
                activate(userPetEnabled)
            }
            is PhoneControlAttentionEvent.RunCompleted -> {
                holdAfterCompletion(userPetEnabled, event.sessionKey, event.runId)
            }
            is PhoneControlAttentionEvent.ReplyViewed -> {
                acknowledgeReply(event.sessionKey, userPetEnabled)
            }
            PhoneControlAttentionEvent.TimerExpired -> {
                clearTimedAttention(userPetEnabled)
            }
            is PhoneControlAttentionEvent.RunForgotten -> {
                forgetRun(event.runId)
                emptySet()
            }
        }
    }

    fun activate(userPetEnabled: Boolean): Set<PhoneControlAttentionEffect> {
        if (!userPetEnabled) {
            overrideVisible = true
            return setOf(PhoneControlAttentionEffect.ShowTransientPet)
        }
        return emptySet()
    }

    fun holdAfterCompletion(userPetEnabled: Boolean, sessionKey: String?, runId: String?): Set<PhoneControlAttentionEffect> {
        val key = sessionKey?.takeIf { it.isNotBlank() }
        attentionSessionKey = key ?: attentionSessionKey
        attentionRunId = runId?.takeIf { it.isNotBlank() } ?: attentionRunId
        key?.let(protectedReplySessions::add)
        if (!userPetEnabled) {
            overrideVisible = true
            return setOf(PhoneControlAttentionEffect.ShowTransientPet)
        }
        return emptySet()
    }

    fun clearTimedAttention(userPetEnabled: Boolean): Set<PhoneControlAttentionEffect> {
        attentionSessionKey = null
        attentionRunId = null
        protectedReplySessions.clear()
        return hideEffectIfOverrideRestored(userPetEnabled)
    }

    fun restoreOverrideIfNeeded(userPetEnabled: Boolean): Set<PhoneControlAttentionEffect> {
        return hideEffectIfOverrideRestored(userPetEnabled)
    }

    fun rememberRun(sessionKey: String?, runId: String?) {
        runId?.takeIf { it.isNotBlank() }?.let(runIds::add)
        attentionSessionKey = sessionKey?.takeIf { it.isNotBlank() } ?: attentionSessionKey
        attentionRunId = runId?.takeIf { it.isNotBlank() } ?: attentionRunId
    }

    fun forgetRun(runId: String?) {
        runId?.takeIf { it.isNotBlank() }?.let(runIds::remove)
    }

    fun isRememberedRun(runId: String?): Boolean {
        return runId?.takeIf { it.isNotBlank() }?.let(runIds::contains) == true
    }

    fun shouldPreserveUnread(sessionKey: String?): Boolean {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return false
        return key in protectedReplySessions
    }

    fun acknowledgeReply(sessionKey: String?, userPetEnabled: Boolean): Set<PhoneControlAttentionEffect> {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return emptySet()
        protectedReplySessions.remove(key)
        if (attentionSessionKey != key) {
            return emptySet()
        }
        attentionSessionKey = null
        attentionRunId = null
        return hideEffectIfOverrideRestored(userPetEnabled)
    }

    private fun hideEffectIfOverrideRestored(userPetEnabled: Boolean): Set<PhoneControlAttentionEffect> {
        if (!overrideVisible || userPetEnabled) {
            overrideVisible = false
            return emptySet()
        }
        overrideVisible = false
        return setOf(PhoneControlAttentionEffect.HideTransientPet)
    }
}
