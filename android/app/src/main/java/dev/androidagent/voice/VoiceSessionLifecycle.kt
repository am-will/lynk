package dev.androidagent.voice

/** Serializes ownership of resources that belong to one realtime voice generation. */
internal class VoiceSessionLifecycle {
    sealed interface Phase {
        data object Idle : Phase
        data class Starting(val generation: Long) : Phase
        data class Active(val generation: Long) : Phase
        data class Stopping(val generation: Long) : Phase
        data class Failed(val generation: Long?, val message: String) : Phase
    }

    private var nextGeneration = 0L
    var phase: Phase = Phase.Idle
        private set

    fun begin(): Long? {
        if (phase !is Phase.Idle && phase !is Phase.Failed) return null
        return (++nextGeneration).also { phase = Phase.Starting(it) }
    }

    fun owns(generation: Long): Boolean = when (val current = phase) {
        is Phase.Starting -> current.generation == generation
        is Phase.Active -> current.generation == generation
        else -> false
    }

    fun activate(generation: Long): Boolean {
        if (!owns(generation)) return false
        phase = Phase.Active(generation)
        return true
    }

    /** Returns true exactly once to the caller responsible for releasing the generation. */
    fun beginStop(generation: Long): Boolean {
        if (!owns(generation)) return false
        phase = Phase.Stopping(generation)
        return true
    }

    fun finishStop(generation: Long, failure: String? = null): Boolean {
        val stopping = phase as? Phase.Stopping ?: return false
        if (stopping.generation != generation) return false
        phase = if (failure == null) Phase.Idle else Phase.Failed(generation, failure)
        return true
    }
}
