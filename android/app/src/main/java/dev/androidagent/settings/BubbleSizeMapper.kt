package dev.androidagent.settings

import dev.androidagent.AppearancePrefs

object BubbleSizeMapper {
    fun progressToDp(progress: Int): Int {
        val p = progress.coerceIn(0, 100)
        return if (p <= 50) {
            AppearancePrefs.MIN_BUBBLE_SIZE_DP +
                ((AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP - AppearancePrefs.MIN_BUBBLE_SIZE_DP) * p) / 50
        } else {
            AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP +
                ((AppearancePrefs.MAX_BUBBLE_SIZE_DP - AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP) * (p - 50)) / 50
        }
    }

    fun dpToProgress(dp: Int): Int {
        val clamped = dp.coerceIn(AppearancePrefs.MIN_BUBBLE_SIZE_DP, AppearancePrefs.MAX_BUBBLE_SIZE_DP)
        return if (clamped <= AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP) {
            val denom = (AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP - AppearancePrefs.MIN_BUBBLE_SIZE_DP).coerceAtLeast(1)
            ((clamped - AppearancePrefs.MIN_BUBBLE_SIZE_DP) * 50) / denom
        } else {
            val denom = (AppearancePrefs.MAX_BUBBLE_SIZE_DP - AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP).coerceAtLeast(1)
            50 + ((clamped - AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP) * 50) / denom
        }
    }

    fun coerceDp(dp: Int): Int = dp.coerceIn(AppearancePrefs.MIN_BUBBLE_SIZE_DP, AppearancePrefs.MAX_BUBBLE_SIZE_DP)
}
