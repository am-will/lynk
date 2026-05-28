package dev.androidagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens.dp
import dev.androidagent.ui.DesignTokens.withAlpha

/**
 * In-modal anchored dropdown.
 *
 * Lives as a child of the modal's [FrameLayout] host so it works inside
 * `TYPE_APPLICATION_OVERLAY` windows (no cross-window popup quirks).
 *
 * Use [show] to present a picker anchored under a pill. The picker:
 * - auto-flips above the anchor when there isn't enough room below
 * - dismisses on outside tap, back press from the host's key listener,
 *   or explicit [dismiss]
 * - supports a title, multiple sections with overline headers, optional
 *   leading icons, a trailing check icon for the active row, and a
 *   destructive row tone
 */
class AnchoredPicker(
    private val context: Context,
    private val tokens: ThemeTokens
) {

    data class Row(
        val id: String? = null,
        val label: String,
        val sublabel: String? = null,
        val iconRes: Int? = null,
        val selected: Boolean = false,
        val selectable: Boolean = true,
        val emphasizeSublabel: Boolean = false,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val badgeCount: Int = 0,
        val trailingIconRes: Int? = null,
        val trailingIconRotation: Float = 0f,
        val dismissOnSelect: Boolean = true,
        val onSelect: () -> Unit
    )

    data class Section(val title: String? = null, val rows: List<Row>)

    private var scrimView: View? = null
    private var sheetView: View? = null
    private var hostRef: FrameLayout? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var currentAnchor: View? = null
    private var currentPreferAbove = false
    private var currentHeightFraction: Float? = null
    private val rowViewsById = mutableMapOf<String, View>()

    val isShowing: Boolean
        get() = sheetView != null

    fun isShowingFor(anchor: View): Boolean = sheetView != null && currentAnchor === anchor

    fun update(
        title: String? = null,
        sections: List<Section>,
        heightFraction: Float? = null,
        preferAbove: Boolean? = null,
        revealRowId: String? = null
    ) {
        val sheet = sheetView as? LinearLayout ?: return
        val scrollY = findBodyScroller(sheet)?.scrollY ?: 0
        currentHeightFraction = heightFraction
        preferAbove?.let { currentPreferAbove = it }
        bindSheetContent(sheet, title, sections)
        applyHeight(sheet, heightFraction)
        sheet.requestLayout()
        preDrawListener?.let {
            sheet.viewTreeObserver.removeOnPreDrawListener(it)
        }
        val observer = sheet.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            val host = hostRef
            val anchor = currentAnchor
            if (host != null && anchor != null && sheet.parent === host && anchor.isAttachedToWindow) {
                positionSheet(host, sheet, anchor)
            }
            sheet.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            preDrawListener = null
            findBodyScroller(sheet)?.scrollTo(0, scrollY)
            revealRowId?.let { rowId ->
                sheet.post { revealRowFullyIfNeeded(rowId) }
            }
            true
        }
        preDrawListener = preDraw
        observer.addOnPreDrawListener(preDraw)
    }

    fun updateRow(row: Row): Boolean {
        val rowId = row.id ?: return false
        val existing = rowViewsById[rowId] ?: return false
        val parent = existing.parent as? ViewGroup ?: return false
        val index = parent.indexOfChild(existing)
        if (index < 0) return false

        val replacement = buildRow(row)
        parent.removeViewAt(index)
        parent.addView(replacement, index)
        return true
    }

    fun reposition() {
        val host = hostRef ?: return
        val sheet = sheetView ?: return
        val anchor = currentAnchor ?: return
        applyHeight(sheet, currentHeightFraction)
        sheet.requestLayout()
        preDrawListener?.let {
            sheet.viewTreeObserver.removeOnPreDrawListener(it)
        }
        val observer = sheet.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (sheet.parent === host && anchor.isAttachedToWindow) {
                positionSheet(host, sheet, anchor)
            }
            sheet.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            preDrawListener = null
            true
        }
        preDrawListener = preDraw
        observer.addOnPreDrawListener(preDraw)
    }

    fun show(
        host: FrameLayout,
        anchor: View,
        title: String? = null,
        sections: List<Section>,
        heightFraction: Float? = null,
        preferAbove: Boolean = false,
        onDismiss: (() -> Unit)? = null
    ) {
        dismiss()
        hostRef = host
        currentAnchor = anchor
        currentPreferAbove = preferAbove
        currentHeightFraction = heightFraction
        onDismissCallback = onDismiss

        val scrim = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener { dismiss() }
        }
        host.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        scrimView = scrim

        val sheet = buildSheet(title, sections, heightFraction)
        val sheetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            heightPx(heightFraction) ?: FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sideMargin = dp(context, DesignTokens.Spacing.md)
            leftMargin = sideMargin
            rightMargin = sideMargin
            topMargin = sideMargin
        }
        host.addView(sheet, sheetParams)
        sheetView = sheet

        sheet.alpha = 0f
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f

        val observer = sheet.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            positionSheet(host, sheet, anchor)
            sheet.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
            sheet.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            preDrawListener = null
            true
        }
        preDrawListener = preDraw
        observer.addOnPreDrawListener(preDraw)
    }

    fun dismiss() {
        val host = hostRef
        val sheet = sheetView
        val scrim = scrimView
        val callback = onDismissCallback
        hostRef = null
        sheetView = null
        scrimView = null
        currentAnchor = null
        currentPreferAbove = false
        currentHeightFraction = null
        onDismissCallback = null
        preDrawListener?.let {
            sheet?.viewTreeObserver?.removeOnPreDrawListener(it)
        }
        preDrawListener = null
        if (sheet != null) {
            sheet.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(110L)
                .withEndAction {
                    (sheet.parent as? ViewGroup)?.removeView(sheet)
                }
                .start()
        }
        scrim?.let { host?.removeView(it) }
        callback?.invoke()
    }

    private fun positionSheet(host: FrameLayout, sheet: View, anchor: View) {
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        val anchorTopInHost = anchorLocation[1] - hostLocation[1]
        val anchorLeftInHost = anchorLocation[0] - hostLocation[0]
        val anchorBottomInHost = anchorTopInHost + anchor.height
        val anchorCenterX = anchorLeftInHost + anchor.width / 2

        val params = sheet.layoutParams as FrameLayout.LayoutParams
        val sideMargin = dp(context, DesignTokens.Spacing.md)
        val gap = dp(context, DesignTokens.Spacing.sm)
        val hostHeight = host.height
        val hostWidth = host.width
        val measuredSheetHeight = sheet.measuredHeight.coerceAtLeast(dp(context, 80))
        val sheetWidth = sheet.measuredWidth.coerceAtLeast(dp(context, 200))
        val minSheetHeight = dp(context, 80)

        val spaceBelow = hostHeight - anchorBottomInHost
        val spaceAbove = anchorTopInHost
        val canPlaceAbove = spaceAbove >= minSheetHeight + gap + sideMargin
        val placeAbove = if (currentPreferAbove && canPlaceAbove) {
            true
        } else {
            spaceBelow < measuredSheetHeight + gap + sideMargin && spaceAbove > spaceBelow
        }

        var effectiveHeight = measuredSheetHeight
        val targetTop: Int
        if (placeAbove) {
            val availableAbove = (anchorTopInHost - sideMargin - gap).coerceAtLeast(minSheetHeight)
            if (measuredSheetHeight > availableAbove) {
                effectiveHeight = availableAbove
                val lp = sheet.layoutParams
                lp.height = availableAbove
                sheet.layoutParams = lp
            }
            // Pin the sheet's bottom edge a small gap above the anchor.
            targetTop = anchorTopInHost - effectiveHeight - gap
        } else {
            val availableBelow = (hostHeight - anchorBottomInHost - sideMargin - gap).coerceAtLeast(minSheetHeight)
            if (measuredSheetHeight > availableBelow) {
                effectiveHeight = availableBelow
                val lp = sheet.layoutParams
                lp.height = availableBelow
                sheet.layoutParams = lp
            }
            targetTop = anchorBottomInHost + gap
        }
        val rawLeft = anchorCenterX - sheetWidth / 2
        val targetLeft = rawLeft
            .coerceAtLeast(sideMargin)
            .coerceAtMost(hostWidth - sheetWidth - sideMargin)
            .coerceAtLeast(sideMargin)

        params.leftMargin = targetLeft
        params.topMargin = targetTop
        params.rightMargin = 0
        sheet.layoutParams = params
        sheet.pivotX = (anchorCenterX - targetLeft).toFloat().coerceIn(0f, sheetWidth.toFloat())
        sheet.pivotY = if (placeAbove) effectiveHeight.toFloat() else 0f
    }

    private fun buildSheet(title: String?, sections: List<Section>, heightFraction: Float?): View {
        val padOuter = dp(context, DesignTokens.Spacing.sm)
        val padTop = dp(context, DesignTokens.Spacing.sm)
        val padBottom = dp(context, DesignTokens.Spacing.sm)
        val display = context.resources.displayMetrics
        val maxWidth = (display.widthPixels - dp(context, DesignTokens.Spacing.lg * 2))
            .coerceAtMost(dp(context, 360))
        val maxHeight = (display.heightPixels * 0.55f).toInt()

        val container = LinearLayout(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_picker_sheet,
                description = title?.let { "$it menu" } ?: "Chat menu"
            )
            orientation = LinearLayout.VERTICAL
            background = Drawables.dropdownSheet(context, tokens)
            elevation = dp(context, DesignTokens.Elevation.popover).toFloat()
            setPadding(padOuter, padTop, padOuter, padBottom)
            clipToPadding = false
            minimumWidth = dp(context, 200)
            maxWidth.let { mw -> layoutParams = ViewGroup.LayoutParams(mw, ViewGroup.LayoutParams.WRAP_CONTENT) }
        }

        bindSheetContent(container, title, sections)
        if (heightFraction == null) {
            container.maxHeight(maxHeight)
        } else {
            applyHeight(container, heightFraction)
        }
        return container
    }

    private fun bindSheetContent(container: LinearLayout, title: String?, sections: List<Section>) {
        rowViewsById.clear()
        container.removeAllViews()
        if (!title.isNullOrBlank()) {
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs), dp(context, DesignTokens.Spacing.xs), dp(context, DesignTokens.Spacing.sm))
                addView(TextView(context).apply {
                    text = title.uppercase()
                    Typography.applyOverline(this, tokens)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(tokens.secondaryText)
                    background = Drawables.pillSurface(context, tokens)
                    backgroundTintList = null
                    exposeToAccessibility(
                        viewId = R.id.openclaw_picker_close_button,
                        description = "Close menu",
                        focusable = true
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                    setOnClickListener { dismiss() }
                }, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)))
            })
        }

        val scroller = ScrollView(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_picker_scroller,
                description = title?.let { "$it options" } ?: "Menu options"
            )
            tag = BODY_SCROLLER_TAG
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroller.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) {
                body.addView(divider(), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 1).coerceAtLeast(1)
                ).apply {
                    topMargin = dp(context, DesignTokens.Spacing.xs)
                    bottomMargin = dp(context, DesignTokens.Spacing.xs)
                })
            }
            section.title?.takeIf { it.isNotBlank() }?.let { headerText ->
                body.addView(TextView(context).apply {
                    text = headerText
                    Typography.applyOverline(this, tokens)
                    setPadding(dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs), dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs))
                })
            }
            section.rows.forEach { row ->
                body.addView(buildRow(row))
            }
        }

        container.addView(scroller)
    }

    private fun findBodyScroller(container: LinearLayout): ScrollView? {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child is ScrollView && child.tag == BODY_SCROLLER_TAG) {
                return child
            }
        }
        return null
    }

    private fun revealRowFullyIfNeeded(rowId: String) {
        val sheet = sheetView as? LinearLayout ?: return
        val scroller = findBodyScroller(sheet) ?: return
        val row = rowViewsById[rowId] ?: return
        if (!row.isAttachedToWindow || row.height <= 0 || scroller.height <= 0) return

        val content = scroller.getChildAt(0) ?: return
        val rowTop = row.topInAncestor(content) ?: return
        val rowBottom = rowTop + row.height
        val visibleTop = scroller.scrollY + scroller.paddingTop
        val visibleBottom = scroller.scrollY + scroller.height - scroller.paddingBottom
        val targetScrollY = when {
            rowTop < visibleTop -> rowTop - scroller.paddingTop
            rowBottom > visibleBottom -> rowBottom - scroller.height + scroller.paddingBottom
            else -> null
        } ?: return

        val contentHeight = scroller.getChildAt(0)?.height ?: 0
        val viewportHeight = (scroller.height - scroller.paddingTop - scroller.paddingBottom).coerceAtLeast(0)
        val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0)
        scroller.smoothScrollTo(0, targetScrollY.coerceIn(0, maxScrollY))
    }

    private fun View.topInAncestor(ancestor: View): Int? {
        var current: View = this
        var topInAncestor = current.top
        var parentView = current.parent as? View ?: return null
        while (parentView !== ancestor) {
            topInAncestor += parentView.top - parentView.scrollY
            current = parentView
            parentView = current.parent as? View ?: return null
        }
        return topInAncestor
    }

    private fun View.maxHeight(maxPx: Int) {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                if (measuredHeight > maxPx) {
                    val lp = layoutParams
                    lp.height = maxPx
                    layoutParams = lp
                }
                return true
            }
        })
    }

    private fun applyHeight(sheet: View, heightFraction: Float?) {
        val params = sheet.layoutParams ?: return
        params.height = heightPx(heightFraction) ?: ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.layoutParams = params
    }

    private fun heightPx(heightFraction: Float?): Int? {
        return heightFraction
            ?.let { fraction -> (context.resources.displayMetrics.heightPixels * fraction.coerceIn(0.1f, 0.9f)).toInt() }
    }

    private fun buildRow(row: Row): View {
        val rowView = LinearLayout(context).apply {
            exposeToAccessibility(
                viewId = R.id.openclaw_picker_row,
                description = row.accessibilityDescription(),
                stateDescription = row.accessibilityStateDescription(),
                focusable = row.enabled
            )
            row.id?.let { setTag(R.id.openclaw_picker_row_key, it) }
            isSelected = row.selected
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.dropdownRowBackground(context, tokens)
            isClickable = row.enabled
            isFocusable = row.enabled
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm)
            )
            alpha = if (row.enabled) 1f else 0.45f
            minimumHeight = dp(context, DesignTokens.Sizes.pickerRow)
            if (row.enabled) {
                setOnClickListener {
                    row.onSelect()
                    if (row.dismissOnSelect) {
                        dismiss()
                    }
                }
            }
        }
        row.id?.let { rowViewsById[it] = rowView }

        row.iconRes?.let { iconRes ->
            val iconFrame = FrameLayout(context).apply {
                hideFromAccessibility()
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (row.destructive) tokens.danger else tokens.secondaryText)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, FrameLayout.LayoutParams(dp(context, 20), dp(context, 20), Gravity.CENTER))
                if (row.badgeCount > 0) {
                    addView(TextView(context).apply {
                        text = badgeText(row.badgeCount)
                        textSize = 9f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(context, 8).toFloat()
                            setColor(0xFFE53935.toInt())
                        }
                        minWidth = dp(context, 16)
                        minHeight = dp(context, 16)
                        setPadding(dp(context, 3), 0, dp(context, 3), 0)
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(context, 16), Gravity.TOP or Gravity.END))
                }
            }
            rowView.addView(iconFrame, LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply {
                rightMargin = dp(context, DesignTokens.Spacing.md)
            })
        }

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(context).apply {
            text = row.label
            Typography.applyCallout(this, tokens)
            if (row.destructive) setTextColor(tokens.danger)
            if (row.selected) setTextColor(tokens.accent)
            isSingleLine = true
        })
        row.sublabel?.takeIf { it.isNotBlank() }?.let { sub ->
            labels.addView(TextView(context).apply {
                text = sub
                Typography.applyCaption(this, tokens)
                if (row.emphasizeSublabel) {
                    setTextColor(tokens.accent)
                    setTypeface(typeface, Typeface.BOLD)
                }
                setPadding(0, dp(context, 2), 0, 0)
                isSingleLine = true
            })
        }
        rowView.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (row.selected) {
            rowView.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(tokens.accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                hideFromAccessibility()
            }, LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                leftMargin = dp(context, DesignTokens.Spacing.md)
            })
        }

        row.trailingIconRes?.let { iconRes ->
            rowView.addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(tokens.secondaryText)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                rotation = row.trailingIconRotation
                hideFromAccessibility()
            }, LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                leftMargin = dp(context, DesignTokens.Spacing.md)
            })
        }

        return rowView
    }

    private fun badgeText(count: Int): String {
        return if (count > 99) "99+" else count.toString()
    }

    private fun Row.accessibilityDescription(): String {
        val parts = listOfNotNull(
            label,
            sublabel?.takeIf { it.isNotBlank() },
            badgeCount.takeIf { it > 0 }?.let { "$it unread" }
        )
        return parts.joinToString(", ")
    }

    private fun Row.accessibilityStateDescription(): String {
        return buildList {
            if (selectable || selected) {
                add(if (selected) "selected" else "not selected")
            }
            if (!enabled) add("disabled")
        }.joinToString(", ")
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(withAlpha(tokens.borderSoft, 0xCC))
    }

    companion object {
        private const val BODY_SCROLLER_TAG = "anchored_picker_body_scroller"
    }
}
