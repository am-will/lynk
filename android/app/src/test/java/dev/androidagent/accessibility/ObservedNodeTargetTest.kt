package dev.androidagent.accessibility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObservedNodeTargetTest {
    @Test
    fun parsesGenerationAndOrdinalTogether() {
        val target = ObservedNodeTarget.parse(JSONObject()
            .put("observationId", "123e4567-e89b-12d3-a456-426614174000")
            .put("nodeId", "n17"))

        assertEquals("123e4567-e89b-12d3-a456-426614174000", target.observationId)
        assertEquals("n17", target.nodeId)
    }

    @Test
    fun rejectsMissingGenerationAndFabricatedNodeIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservedNodeTarget.parse(JSONObject().put("nodeId", "n1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObservedNodeTarget.parse(JSONObject()
                .put("observationId", "123e4567-e89b-12d3-a456-426614174000")
                .put("nodeId", "button-submit"))
        }
    }
}
