package dev.androidagent.settings.screens

import android.content.Context
import dev.androidagent.ui.DesignTokens

data class HubLayoutMetrics(
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val statusGridHeightDp: Int,
    val categoryIconSizeDp: Int,
    val headerAvatarSizeDp: Int,
    val statusChipIconSizeDp: Int
)

/** Scale hub spacing from the shell content area so the grid and rows fill without scrolling. */
fun hubLayoutMetrics(context: Context): HubLayoutMetrics {
    val dm = context.resources.displayMetrics
    val heightDp = (dm.heightPixels / dm.density).toInt()
    val widthDp = (dm.widthPixels / dm.density).toInt()
    val compact = heightDp < 700 || widthDp <= 400
    val bottomNavReserveDp = 68
    val contentHeightDp = (heightDp - bottomNavReserveDp).coerceAtLeast(520)

    val horizontalPadding = if (compact) DesignTokens.Spacing.lg else DesignTokens.Spacing.xl
    val verticalPadding = if (compact) DesignTokens.Spacing.md else DesignTokens.Spacing.lg
    val headerReserve = if (compact) 88 else 108
    val sectionGaps = DesignTokens.Spacing.lg * 2
    val categoryGaps = (DesignTokens.Spacing.sm + 2) * 4
    val categoryReserve = (contentHeightDp - verticalPadding * 2 - headerReserve - sectionGaps - categoryGaps)
        .coerceAtLeast(240)
    val statusGridHeight = (categoryReserve * 0.24f).toInt().coerceIn(
        if (compact) 108 else 132,
        if (compact) 124 else 156
    )

    return HubLayoutMetrics(
        horizontalPaddingDp = horizontalPadding,
        verticalPaddingDp = verticalPadding,
        statusGridHeightDp = statusGridHeight,
        categoryIconSizeDp = if (compact) 38 else 44,
        headerAvatarSizeDp = if (compact) 52 else 64,
        statusChipIconSizeDp = if (compact) 15 else 16
    )
}
