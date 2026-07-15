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
    fun phoneRequestsAdvertiseDirectPhoneToolsWithoutSkillPrerequisite() {
        val descriptions = LocalToolSpecs.descriptions(
            profile(supportsImageInput = true),
            LocalToolAccess(phoneControl = true)
        )
        val ids = (0 until descriptions.length())
            .map { descriptions.getJSONObject(it).getString("id") }
            .toSet()

        assertTrue(ids.contains("phone_open_app"))
        assertTrue(!ids.contains("local_read_skill"))
        assertTrue(!ids.contains("termux_command"))
        assertTrue((0 until descriptions.length()).none {
            descriptions.getJSONObject(it).getString("description").contains("local_read_skill")
        })
    }

    @Test
    fun toolDescriptionsAreDerivedFromSpecs() {
        val descriptions = LocalToolSpecs.descriptions(profile(supportsImageInput = true))
        val ids = (0 until descriptions.length())
            .map { descriptions.getJSONObject(it).getString("id") }
            .toSet()

        assertEquals(LocalToolSpecs.all.map { it.id }.toSet(), ids)
        assertTrue(ids.contains("phone_long_press_node"))
        assertTrue(ids.contains("phone_wait"))
        assertTrue(ids.contains("local_read_skill"))
        val skillDescription = (0 until descriptions.length())
            .map(descriptions::getJSONObject)
            .single { it.getString("id") == "local_read_skill" }
            .getString("description")
        assertTrue(!skillDescription.contains("android-control"))
        assertTrue(!skillDescription.contains("phone-control"))
    }

    @Test
    fun textOnlyRuntimeDoesNotAdvertiseImageDependentTools() {
        val descriptions = LocalToolSpecs.descriptions(profile(supportsImageInput = false))
        val ids = (0 until descriptions.length())
            .map { descriptions.getJSONObject(it).getString("id") }
            .toSet()

        assertTrue("phone_take_screenshot" !in ids)
        assertTrue("phone_tap_normalized" !in ids)
        assertTrue("phone_observe" in ids)
        assertTrue("phone_tap_node" in ids)
    }

    private fun profile(supportsImageInput: Boolean) = LocalModelRuntimeProfile(
        kind = LocalModelRuntimeKind.LiteRtLm,
        effectiveContextTokens = 4096,
        supportsImageInput = supportsImageInput
    )
}
