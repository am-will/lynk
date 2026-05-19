package dev.androidagent.settings

import dev.androidagent.AppearancePrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleSizeMapperTest {
    @Test
    fun progressMapsThroughMinDefaultAndMax() {
        assertEquals(AppearancePrefs.MIN_BUBBLE_SIZE_DP, BubbleSizeMapper.progressToDp(0))
        assertEquals(AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP, BubbleSizeMapper.progressToDp(50))
        assertEquals(AppearancePrefs.MAX_BUBBLE_SIZE_DP, BubbleSizeMapper.progressToDp(100))
    }

    @Test
    fun dpMapsBackToProgress() {
        assertEquals(0, BubbleSizeMapper.dpToProgress(AppearancePrefs.MIN_BUBBLE_SIZE_DP))
        assertEquals(50, BubbleSizeMapper.dpToProgress(AppearancePrefs.DEFAULT_BUBBLE_SIZE_DP))
        assertEquals(100, BubbleSizeMapper.dpToProgress(AppearancePrefs.MAX_BUBBLE_SIZE_DP))
    }

    @Test
    fun valuesAreClampedAtBounds() {
        assertEquals(AppearancePrefs.MIN_BUBBLE_SIZE_DP, BubbleSizeMapper.coerceDp(-1))
        assertEquals(AppearancePrefs.MAX_BUBBLE_SIZE_DP, BubbleSizeMapper.coerceDp(10_000))
        assertEquals(AppearancePrefs.MIN_BUBBLE_SIZE_DP, BubbleSizeMapper.progressToDp(-50))
        assertEquals(100, BubbleSizeMapper.dpToProgress(10_000))
    }
}
