package dev.androidagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelKeyboardLayoutTest {
    @Test
    fun defaultBoundsUsesPopupFractionOrFullscreenHeight() {
        assertEquals(
            PanelBounds(height = 820, y = 180),
            PanelKeyboardLayout.defaultBounds(
                displayHeight = 1000,
                presentation = PanelPresentation.Popup,
                popupHeightFraction = 0.82f,
                fullscreenHeight = 940
            )
        )
        assertEquals(
            PanelBounds(height = 940, y = 0),
            PanelKeyboardLayout.defaultBounds(
                displayHeight = 1000,
                presentation = PanelPresentation.Fullscreen,
                popupHeightFraction = 0.82f,
                fullscreenHeight = 940
            )
        )
    }

    @Test
    fun adjustedBoundsMovesPanelAboveKeyboard() {
        val adjusted = PanelKeyboardLayout.adjustedBoundsAboveKeyboard(
            defaultBounds = PanelBounds(height = 820, y = 180),
            keyboardTop = 700,
            minPanelHeight = 300,
            minY = 8,
            composerGap = 4,
            minHeight = 240
        )

        assertEquals(PanelBounds(height = 516, y = 180), adjusted)
    }

    @Test
    fun adjustedBoundsReturnsNullWhenKeyboardOverlapIsTooSmall() {
        assertNull(
            PanelKeyboardLayout.adjustedBoundsAboveKeyboard(
                defaultBounds = PanelBounds(height = 820, y = 180),
                keyboardTop = 930,
                minPanelHeight = 300,
                minY = 8,
                composerGap = 4,
                minHeight = 240
            )
        )
    }

    @Test
    fun keyboardEstimateAndGapsRespectBoundsAndPresentation() {
        assertEquals(420, PanelKeyboardLayout.estimatedKeyboardHeight(1000, fraction = 0.485f, minHeight = 260, maxFraction = 0.42f))
        assertEquals(4, PanelKeyboardLayout.composerGap(baseGap = 4, fullscreenExtraGap = 8, PanelPresentation.Popup))
        assertEquals(12, PanelKeyboardLayout.composerGap(baseGap = 4, fullscreenExtraGap = 8, PanelPresentation.Fullscreen))
        assertEquals(0, PanelKeyboardLayout.bottomClearance(fullscreenClearance = 28, PanelPresentation.Popup))
        assertEquals(28, PanelKeyboardLayout.bottomClearance(fullscreenClearance = 28, PanelPresentation.Fullscreen))
    }
}
