package dev.androidagent.debug

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DebugRuntimeLog {
    fun write(hypothesisId: String, location: String, message: String, data: Map<String, Any?>) {
        Thread {
            runCatching {
                val payload = JSONObject().apply {
                    put("sessionId", SESSION_ID)
                    put("runId", RUN_ID)
                    put("hypothesisId", hypothesisId)
                    put("location", location)
                    put("message", message)
                    put("data", JSONObject(data))
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Debug-Session-Id", SESSION_ID)
                connection.doOutput = true
                connection.outputStream.use { stream -> stream.write(payload.toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
                connection.disconnect()
            }
        }.start()
    }

    private const val ENDPOINT = "http://127.0.0.1:7837/ingest/4052aa84-fb93-478a-bce2-a86b2ed750c1"
    private const val SESSION_ID = "a04143"
    private const val RUN_ID = "keyboard-overlap"
}
