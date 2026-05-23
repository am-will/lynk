package dev.androidagent.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class DiagnosticsEventLevel {
    Info,
    Success,
    Warning,
    Error
}

data class DiagnosticsEvent(
    val timestampMs: Long,
    val level: DiagnosticsEventLevel,
    val message: String
) {
    fun formatted(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
        return "$time  $message"
    }
}

object DiagnosticsEventLog {
    private const val MAX_EVENTS = 50
    private val events = CopyOnWriteArrayList<DiagnosticsEvent>()

    fun append(level: DiagnosticsEventLevel, message: String) {
        events.add(0, DiagnosticsEvent(System.currentTimeMillis(), level, message))
        while (events.size > MAX_EVENTS) {
            events.removeAt(events.lastIndex)
        }
    }

    fun recent(): List<DiagnosticsEvent> = events.toList()

    fun clear() {
        events.clear()
    }

    fun exportText(): String {
        return recent().joinToString("\n") { it.formatted() }
    }
}

object DiagnosticsPrefsStore {
    private const val PREFS = "open_claw_agent_diagnostics"
    private const val LOG_LEVEL = "log_level"

    enum class LogLevel(val key: String, val label: String) {
        Debug("debug", "Debug"),
        Info("info", "Info");

        companion object {
            fun fromKey(value: String?): LogLevel =
                values().firstOrNull { it.key == value } ?: Debug
        }
    }

    fun logLevel(context: android.content.Context): LogLevel {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return LogLevel.fromKey(prefs.getString(LOG_LEVEL, LogLevel.Debug.key))
    }

    fun setLogLevel(context: android.content.Context, level: LogLevel) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(LOG_LEVEL, level.key)
            .apply()
    }
}
