package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolSpecsTest {
    @Test
    fun advertisedPhoneToolsMatchExecutablePhoneTools() {
        val advertisedPhoneToolIds = LocalToolSpecs.all
            .filter { it.group == "phone" }
            .map { it.id }
            .toSet()

        assertEquals(advertisedPhoneToolIds, LocalToolSpecs.phoneCommandsByToolId.keys)
    }

    @Test
    fun toolDescriptionsAreDerivedFromSpecs() {
        val descriptions = LocalToolSpecs.descriptions()
        val ids = (0 until descriptions.length())
            .map { descriptions.getJSONObject(it).getString("id") }
            .toSet()

        assertEquals(LocalToolSpecs.all.map { it.id }.toSet(), ids)
        assertTrue(ids.contains("phone_long_press_node"))
        assertTrue(ids.contains("phone_wait"))
    }
}
