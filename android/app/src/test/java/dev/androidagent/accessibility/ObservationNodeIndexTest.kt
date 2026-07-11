package dev.androidagent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationNodeIndexTest {
    @Test
    fun ordinalIdsCannotCrossObservationGenerations() {
        val disposed = mutableListOf<String>()
        val index = ObservationNodeIndex<String>(disposed::add)
        index.begin("observation-a")
        index.put("n1", "first-screen-node")
        assertEquals("first-screen-node", (index.lookup("observation-a", "n1") as ObservationNodeLookup.Found).value)

        index.begin("observation-b")
        index.put("n1", "second-screen-node")

        val stale = index.lookup("observation-a", "n1")
        assertTrue(stale is ObservationNodeLookup.StaleObservation)
        assertEquals("observation-b", (stale as ObservationNodeLookup.StaleObservation).currentObservationId)
        assertEquals("second-screen-node", (index.lookup("observation-b", "n1") as ObservationNodeLookup.Found).value)
        assertEquals(listOf("first-screen-node"), disposed)
    }

    @Test
    fun unknownNodeIsDistinctFromStaleObservation() {
        val index = ObservationNodeIndex<String>()
        index.begin("observation-a")

        assertEquals(ObservationNodeLookup.UnknownNode, index.lookup("observation-a", "n9"))
        assertTrue(index.lookup("observation-old", "n9") is ObservationNodeLookup.StaleObservation)
    }
}
