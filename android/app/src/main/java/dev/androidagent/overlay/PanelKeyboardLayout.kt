package dev.androidagent.overlay

data class PanelBounds(val height: Int, val y: Int)

object PanelKeyboardLayout {
    fun defaultBounds(
        displayHeight: Int,
        presentation: PanelPresentation,
        popupHeightFraction: Float,
        fullscreenHeight: Int
    ): PanelBounds {
        return when (presentation) {
            PanelPresentation.Popup -> {
                val height = (displayHeight * popupHeightFraction).toInt()
                PanelBounds(height = height, y = displayHeight - height)
            }
            PanelPresentation.Fullscreen,
            PanelPresentation.Shell -> PanelBounds(height = fullscreenHeight, y = 0)
        }
    }

    fun adjustedBoundsAboveKeyboard(
        defaultBounds: PanelBounds,
        keyboardTop: Int,
        minKeyboardOverlap: Int,
        minY: Int,
        composerGap: Int,
        minHeight: Int
    ): PanelBounds? {
        val defaultBottom = defaultBounds.y + defaultBounds.height
        if (defaultBottom - keyboardTop < minKeyboardOverlap) {
            return null
        }
        val anchoredHeight = keyboardTop - defaultBounds.y - composerGap
        if (anchoredHeight >= minHeight) {
            return PanelBounds(
                height = anchoredHeight.coerceAtMost(defaultBounds.height),
                y = defaultBounds.y
            )
        }

        val desiredY = (keyboardTop - minHeight - composerGap).coerceAtLeast(minY)
        val desiredHeight = keyboardTop - desiredY - composerGap
        if (desiredHeight < minHeight) {
            return null
        }
        return PanelBounds(height = desiredHeight, y = desiredY)
    }

    fun composerGap(baseGap: Int, fullscreenExtraGap: Int, presentation: PanelPresentation): Int {
        return baseGap + if (presentation == PanelPresentation.Fullscreen || presentation == PanelPresentation.Shell) {
            fullscreenExtraGap
        } else {
            0
        }
    }

    fun bottomClearance(fullscreenClearance: Int, presentation: PanelPresentation): Int {
        return if (presentation == PanelPresentation.Fullscreen || presentation == PanelPresentation.Shell) {
            fullscreenClearance
        } else {
            0
        }
    }

    fun fullscreenKeyboardSpacerHeight(
        defaultBounds: PanelBounds,
        keyboardTop: Int,
        bottomClearance: Int
    ): Int {
        val defaultBottom = defaultBounds.y + defaultBounds.height
        val keyboardOverlap = (defaultBottom - keyboardTop).coerceAtLeast(0)
        return if (keyboardOverlap == 0) 0 else keyboardOverlap + bottomClearance
    }

    fun estimatedKeyboardHeight(displayHeight: Int, fraction: Float, minHeight: Int, maxFraction: Float): Int {
        return (displayHeight * fraction).toInt()
            .coerceIn(minHeight, (displayHeight * maxFraction).toInt())
    }
}
