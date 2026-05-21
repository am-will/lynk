package dev.androidagent.ui

import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat

fun <T : View> T.exposeToAccessibility(
    viewId: Int? = null,
    description: CharSequence? = null,
    stateDescription: CharSequence? = null,
    focusable: Boolean? = null,
    liveRegion: Int? = null
): T {
    viewId?.let { id = it }
    description?.let { contentDescription = it }
    focusable?.let { isFocusable = it }
    liveRegion?.let { accessibilityLiveRegion = it }
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    updateAccessibilityState(description = description, stateDescription = stateDescription)
    return this
}

fun <T : View> T.hideFromAccessibility(): T {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    contentDescription = null
    return this
}

fun View.updateAccessibilityState(
    description: CharSequence? = null,
    stateDescription: CharSequence? = null
) {
    description?.let { contentDescription = it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        this.stateDescription = stateDescription
    } else if (!stateDescription.isNullOrBlank() && !description.isNullOrBlank()) {
        contentDescription = "$description, $stateDescription"
    }
}

fun View.labelFor(target: View) {
    ViewCompat.setLabelFor(this, target.id)
}
