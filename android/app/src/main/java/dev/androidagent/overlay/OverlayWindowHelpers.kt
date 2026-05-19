package dev.androidagent.overlay

import android.view.View
import android.view.WindowManager

fun isOverlayAttached(view: View?): Boolean {
    return view?.isAttachedToWindow == true || view?.parent != null
}

fun detachOverlayView(windowManager: WindowManager, view: View?) {
    view ?: return
    view.animate().cancel()
    view.animate().setListener(null)
    if (isOverlayAttached(view)) {
        runCatching { windowManager.removeViewImmediate(view) }
            .recoverCatching { windowManager.removeView(view) }
    }
}
