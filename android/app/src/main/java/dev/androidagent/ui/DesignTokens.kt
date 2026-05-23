package dev.androidagent.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue

/**
 * Single source of truth for the entire app's visual language.
 *
 * Both the overlay (system overlay window) and the settings Activity read from here.
 * Colors lean iMessage-glassy: neutral surfaces, one brand accent used sparingly,
 * translucent surfaces and soft hairline borders.
 */
data class ThemeTokens(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceElevated: Int,
    val surfaceGlass: Int,
    val surfaceInset: Int,
    val border: Int,
    val borderSoft: Int,
    val highlight: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val tertiaryText: Int,
    val accent: Int,
    val accentInk: Int,
    val accentSoft: Int,
    val accentMuted: Int,
    val success: Int,
    val warning: Int,
    val danger: Int,
    val dangerSoft: Int,
    val bubbleUser: Int,
    val bubbleUserInk: Int,
    val bubbleAssistant: Int,
    val bubbleAssistantInk: Int,
    val bubbleSystem: Int,
    val bubbleSystemInk: Int,
    val scrim: Int,
    val shadowKey: Int
)

object DesignTokens {

    object Radius {
        const val xs = 4
        const val sm = 12
        const val md = 18
        const val lg = 22
        const val xl = 28
        const val pill = 999
    }

    object Spacing {
        const val xs = 4
        const val sm = 8
        const val md = 12
        const val lg = 16
        const val xl = 20
        const val xxl = 24
        const val xxxl = 32
    }

    /** sp values (use `.toFloat()` when setting `textSize`). */
    object Text {
        const val caption = 11f
        const val footnote = 12f
        const val body = 14f
        const val callout = 15f
        const val headline = 17f
        const val title = 22f
        const val largeTitle = 28f
    }

    object Elevation {
        const val flat = 0
        const val low = 2
        const val mid = 8
        const val high = 16
        const val popover = 24
    }

    object Sizes {
        const val control = 36
        const val compact = 30         // composer-row controls (must fit on narrow screens)
        const val compactAction = 36   // composer send button
        const val action = 44
        const val bubble = 56
        const val composerMin = 56
        const val pickerRow = 44
        const val trash = 64
    }

    fun resolve(context: Context): ThemeTokens {
        return if (isNightMode(context)) dark() else light()
    }

    /** Force dark tokens regardless of device night mode. */
    fun darkOnly(): ThemeTokens = dark()

    fun isNightMode(context: Context): Boolean {
        val cfg = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.nightMode == UiModeManager.MODE_NIGHT_YES
        } else false
        return cfg || sys
    }

    private fun light(): ThemeTokens = ThemeTokens(
        isDark = false,
        background = 0xFFF4F6FB.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceElevated = 0xFFFFFFFF.toInt(),
        surfaceGlass = 0xF2FFFFFF.toInt(),
        surfaceInset = 0xFFEEF1F6.toInt(),
        border = 0xFFD9DEE7.toInt(),
        borderSoft = 0xFFE8ECF2.toInt(),
        highlight = 0xFFFFFFFF.toInt(),
        primaryText = 0xFF0F172A.toInt(),
        secondaryText = 0xFF5B6478.toInt(),
        tertiaryText = 0xFF8B93A4.toInt(),
        accent = 0xFF245BFF.toInt(),
        accentInk = 0xFFFFFFFF.toInt(),
        accentSoft = 0x14245BFF,
        accentMuted = 0xFF7E96E8.toInt(),
        success = 0xFF16A34A.toInt(),
        warning = 0xFFD97706.toInt(),
        danger = 0xFFDC2626.toInt(),
        dangerSoft = 0x14DC2626,
        bubbleUser = 0xFF245BFF.toInt(),
        bubbleUserInk = 0xFFFFFFFF.toInt(),
        bubbleAssistant = 0xFFEFF2F7.toInt(),
        bubbleAssistantInk = 0xFF0F172A.toInt(),
        bubbleSystem = 0x1ADC2626,
        bubbleSystemInk = 0xFF8C2018.toInt(),
        scrim = 0x66000000,
        shadowKey = 0x33000000
    )

    private fun dark(): ThemeTokens = ThemeTokens(
        isDark = true,
        background = 0xFF070B14.toInt(),
        surface = 0xFF111827.toInt(),
        surfaceElevated = 0xFF131A2B.toInt(),
        surfaceGlass = 0xE6131A2B.toInt(),
        surfaceInset = 0xFF0C1322.toInt(),
        border = 0xFF1F2A40.toInt(),
        borderSoft = 0xFF1A2438.toInt(),
        highlight = 0x14FFFFFF,
        primaryText = 0xFFF1F5F9.toInt(),
        secondaryText = 0xFF94A3B8.toInt(),
        tertiaryText = 0xFF64748B.toInt(),
        accent = 0xFF2DD4BF.toInt(),
        accentInk = 0xFF06101E.toInt(),
        accentSoft = 0x332DD4BF,
        accentMuted = 0xFF14B8A6.toInt(),
        success = 0xFF34D399.toInt(),
        warning = 0xFFFBBF24.toInt(),
        danger = 0xFFF87171.toInt(),
        dangerSoft = 0x24F87171,
        bubbleUser = 0xFF2DD4BF.toInt(),
        bubbleUserInk = 0xFF06101E.toInt(),
        bubbleAssistant = 0xFF1B2438.toInt(),
        bubbleAssistantInk = 0xFFF1F5F9.toInt(),
        bubbleSystem = 0x33F87171,
        bubbleSystemInk = 0xFFFCA5A5.toInt(),
        scrim = 0x99000000.toInt(),
        shadowKey = 0x99000000.toInt()
    )

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun blend(start: Int, end: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        val inverse = 1f - amount
        return Color.argb(
            (Color.alpha(start) * inverse + Color.alpha(end) * amount).toInt(),
            (Color.red(start) * inverse + Color.red(end) * amount).toInt(),
            (Color.green(start) * inverse + Color.green(end) * amount).toInt(),
            (Color.blue(start) * inverse + Color.blue(end) * amount).toInt()
        )
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpF(context: Context, value: Int): Float =
        value * context.resources.displayMetrics.density

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )
}
