package dev.androidagent.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class ScreenObserver {
    private val nodesById = linkedMapOf<String, AccessibilityNodeInfo>()
    var currentObservationId: String? = null
        private set
    private var currentObservation: JSONObject? = null

    fun node(id: String): AccessibilityNodeInfo? = nodesById[id]

    fun observe(service: PhoneAccessibilityService): JSONObject {
        clearNodes()
        val observationId = java.util.UUID.randomUUID().toString()
        currentObservationId = observationId
        val root = service.rootInActiveWindow
        val nodes = JSONArray()
        val summary = mutableListOf<String>()
        val packageName = root?.packageName?.toString().orEmpty()
        try {
            if (root != null) {
                walk(root, nodes, summary, 0)
            }
        } finally {
            root?.recycle()
        }
        return JSONObject()
            .put("observationId", observationId)
            .put("deviceId", android.os.Build.MODEL)
            .put("package", packageName)
            .put("activity", service.lastActivityClassName ?: "")
            .put("display", displayJson(service))
            .put("screenSummary", summary.take(12).joinToString(" | "))
            .put("nodes", nodes)
            .also { currentObservation = JSONObject(it.toString()) }
    }

    fun observationSnapshot(): JSONObject? = currentObservation?.let { JSONObject(it.toString()) }

    fun clearNodes() {
        nodesById.values.forEach { it.recycle() }
        nodesById.clear()
        currentObservationId = null
        currentObservation = null
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray, summary: MutableList<String>, depth: Int) {
        if (nodesById.size >= MAX_NODES || depth > MAX_DEPTH) {
            return
        }
        val text = node.text?.toString().orEmpty()
        val contentDescription = node.contentDescription?.toString().orEmpty()
        val viewIdResourceName = node.viewIdResourceName?.toString().orEmpty()
        val packageName = node.packageName?.toString().orEmpty()
        val stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString().orEmpty()
        } else {
            ""
        }
        if (text.isNotBlank()) {
            summary.add(text)
        } else if (contentDescription.isNotBlank()) {
            summary.add(contentDescription)
        } else if (stateDescription.isNotBlank()) {
            summary.add(stateDescription)
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val includeNode = text.isNotBlank() ||
            contentDescription.isNotBlank() ||
            viewIdResourceName.isNotBlank() ||
            stateDescription.isNotBlank() ||
            node.isClickable ||
            node.isLongClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isCheckable ||
            node.isChecked ||
            node.isSelected ||
            node.isFocused
        if (!rect.isEmpty && includeNode) {
            val id = "n${nodesById.size + 1}"
            nodesById[id] = AccessibilityNodeInfo.obtain(node)
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("viewIdResourceName", viewIdResourceName)
                    .put("packageName", packageName)
                    .put("text", text)
                    .put("contentDescription", contentDescription)
                    .put("stateDescription", stateDescription)
                    .put("className", node.className?.toString().orEmpty())
                    .put("clickable", node.isClickable)
                    .put("longClickable", node.isLongClickable)
                    .put("scrollable", node.isScrollable)
                    .put("editable", node.isEditable)
                    .put("checkable", node.isCheckable)
                    .put("checked", node.isChecked)
                    .put("selected", node.isSelected)
                    .put("enabled", node.isEnabled)
                    .put("focused", node.isFocused)
                    .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, out, summary, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    companion object {
        private const val MAX_NODES = 240
        private const val MAX_DEPTH = 16

        fun displayJson(service: PhoneAccessibilityService): JSONObject {
            val size = realDisplaySize(service)
            val displayMetrics = service.resources.displayMetrics
            return JSONObject()
                .put("widthPx", size.widthPx)
                .put("heightPx", size.heightPx)
                .put("density", displayMetrics.density)
                .put("densityDpi", displayMetrics.densityDpi)
        }

        fun realDisplaySize(service: PhoneAccessibilityService): DisplaySize {
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return DisplaySize(bounds.width(), bounds.height())
                }
            }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return DisplaySize(metrics.widthPixels, metrics.heightPixels)
            }

            val fallback = service.resources.displayMetrics
            return DisplaySize(fallback.widthPixels, fallback.heightPixels)
        }
    }
}

data class DisplaySize(val widthPx: Int, val heightPx: Int)
