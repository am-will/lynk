package dev.androidagent.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDevicePolicyTest {
    @Test
    fun api26Through30NeverUseSocModelPolicy() {
        for (sdkInt in 26..30) {
            assertFalse(GgufDevicePolicy.shouldDisableVulkan(sdkInt, "SM8850"))
        }
    }

    @Test
    fun api31AndNewerDisableVulkanForSm8850() {
        assertTrue(GgufDevicePolicy.shouldDisableVulkan(31, "SM8850"))
        assertTrue(GgufDevicePolicy.shouldDisableVulkan(36, "sm8850"))
    }

    @Test
    fun api31AndNewerKeepVulkanForOtherOrMissingSocModels() {
        assertFalse(GgufDevicePolicy.shouldDisableVulkan(31, "SM8750"))
        assertFalse(GgufDevicePolicy.shouldDisableVulkan(31, null))
    }
}
