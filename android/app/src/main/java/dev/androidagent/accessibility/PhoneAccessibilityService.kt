package dev.androidagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class PhoneAccessibilityService : AccessibilityService() {
    var lastActivityClassName: String? = null
        private set
    var lastPackageName: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val className = event?.className?.toString()
        if (!className.isNullOrBlank()) {
            lastActivityClassName = className
        }
        val packageName = event?.packageName?.toString()
        if (!packageName.isNullOrBlank()) {
            lastPackageName = packageName
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }
}
