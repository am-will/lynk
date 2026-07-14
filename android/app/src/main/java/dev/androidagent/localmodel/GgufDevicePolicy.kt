package dev.androidagent.localmodel

import android.os.Build

internal object GgufDevicePolicy {
    private const val UNSTABLE_VULKAN_SOC = "SM8850"

    fun shouldDisableVulkan(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return shouldDisableVulkan(Build.VERSION.SDK_INT, Build.SOC_MODEL)
    }

    internal fun shouldDisableVulkan(sdkInt: Int, socModel: String?): Boolean =
        sdkInt >= Build.VERSION_CODES.S &&
            socModel.equals(UNSTABLE_VULKAN_SOC, ignoreCase = true)
}
