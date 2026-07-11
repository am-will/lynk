package dev.androidagent.accessibility

internal sealed interface ObservationNodeLookup<out T> {
    data class Found<T>(val value: T) : ObservationNodeLookup<T>
    data class StaleObservation(val currentObservationId: String?) : ObservationNodeLookup<Nothing>
    data object UnknownNode : ObservationNodeLookup<Nothing>
}

/** Ordinal node IDs are valid only inside one explicit observation generation. */
internal class ObservationNodeIndex<T>(private val dispose: (T) -> Unit = {}) {
    private val values = linkedMapOf<String, T>()
    var observationId: String? = null
        private set

    val size: Int
        get() = values.size

    fun begin(observationId: String) {
        clear()
        this.observationId = observationId
    }

    fun put(nodeId: String, value: T) {
        require(observationId != null) { "Begin an observation before adding nodes" }
        values.put(nodeId, value)?.let(dispose)
    }

    fun lookup(observationId: String, nodeId: String): ObservationNodeLookup<T> {
        if (this.observationId != observationId) {
            return ObservationNodeLookup.StaleObservation(this.observationId)
        }
        return values[nodeId]?.let { ObservationNodeLookup.Found(it) } ?: ObservationNodeLookup.UnknownNode
    }

    fun clear() {
        values.values.forEach(dispose)
        values.clear()
        observationId = null
    }
}
