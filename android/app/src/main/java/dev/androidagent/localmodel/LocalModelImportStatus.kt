package dev.androidagent.localmodel

// Only one screen is visible at a time, so a single overwrite-on-build slot is enough.
object LocalModelImportStatus {
    @Volatile
    private var listener: ((String?) -> Unit)? = null

    fun observe(onUpdate: (String?) -> Unit) {
        listener = onUpdate
    }

    fun clearObserver(onUpdate: (String?) -> Unit) {
        if (listener === onUpdate) listener = null
    }

    fun publish(status: String?) {
        listener?.invoke(status)
    }
}
