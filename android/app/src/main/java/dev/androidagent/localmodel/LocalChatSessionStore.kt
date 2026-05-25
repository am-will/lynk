package dev.androidagent.localmodel

import android.content.Context
import dev.androidagent.chat.ChatAttachmentPreview
import dev.androidagent.chat.ChatAttachmentPreviewJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val attachments: List<ChatAttachmentPreview> = emptyList()
)

data class LocalChatSession(
    val key: String,
    val label: String,
    val updatedAt: Long,
    val messages: List<LocalChatMessage>
)

class LocalChatSessionStore(context: Context) {
    private val file = File(context.filesDir, "local-chat-sessions.json")

    fun all(): List<LocalChatSession> {
        if (!file.isFile) return listOf(defaultSession())
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return listOf(defaultSession())
        val sessions = root.optJSONArray("sessions") ?: return listOf(defaultSession())
        return buildList {
            for (index in 0 until sessions.length()) {
                parseSession(sessions.optJSONObject(index))?.let { add(it) }
            }
        }.ifEmpty { listOf(defaultSession()) }.sortedByDescending { it.updatedAt }
    }

    fun session(key: String?): LocalChatSession {
        val sessions = all()
        return key?.let { requested -> sessions.firstOrNull { it.key == requested } } ?: sessions.first()
    }

    fun create(label: String? = null): LocalChatSession {
        val now = System.currentTimeMillis()
        val session = LocalChatSession(
            key = "local:${UUID.randomUUID()}",
            label = label?.takeIf { it.isNotBlank() } ?: "Local chat",
            updatedAt = now,
            messages = emptyList()
        )
        save(upsert(all(), session))
        return session
    }

    fun append(
        sessionKey: String,
        role: String,
        text: String,
        id: String = "${role}_${UUID.randomUUID()}",
        attachments: List<ChatAttachmentPreview> = emptyList()
    ): LocalChatSession {
        val sessions = all()
        val current = sessions.firstOrNull { it.key == sessionKey } ?: defaultSession(sessionKey)
        val message = LocalChatMessage(id, role, text, System.currentTimeMillis(), attachments)
        val updated = current.copy(
            updatedAt = message.timestamp,
            label = if (current.messages.isEmpty() && role == "user") text.take(40).ifBlank { current.label } else current.label,
            messages = current.messages + message
        )
        save(upsert(sessions, updated))
        return updated
    }

    private fun save(sessions: List<LocalChatSession>) {
        val root = JSONObject().put("sessions", JSONArray().also { array ->
            sessions.forEach { array.put(it.toJson()) }
        })
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    private fun upsert(sessions: List<LocalChatSession>, session: LocalChatSession): List<LocalChatSession> {
        val filtered = sessions.filterNot { it.key == session.key }
        return (listOf(session) + filtered).sortedByDescending { it.updatedAt }
    }

    private fun parseSession(value: JSONObject?): LocalChatSession? {
        value ?: return null
        val key = value.optString("key").takeIf { it.isNotBlank() } ?: return null
        val messages = value.optJSONArray("messages")
        return LocalChatSession(
            key = key,
            label = value.optString("label", "Local chat"),
            updatedAt = value.optLong("updatedAt", 0L),
            messages = buildList {
                if (messages != null) {
                    for (index in 0 until messages.length()) {
                        parseMessage(messages.optJSONObject(index))?.let { add(it) }
                    }
                }
            }
        )
    }

    private fun parseMessage(value: JSONObject?): LocalChatMessage? {
        value ?: return null
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
        val role = value.optString("role").takeIf { it.isNotBlank() } ?: return null
        val text = value.optString("text")
        val attachments = ChatAttachmentPreviewJson.fromJsonArray(value.optJSONArray("attachments"))
        if (text.isBlank() && attachments.isEmpty()) return null
        return LocalChatMessage(
            id = id,
            role = role,
            text = text,
            timestamp = value.optLong("timestamp", System.currentTimeMillis()),
            attachments = attachments
        )
    }

    private fun LocalChatSession.toJson(): JSONObject =
        JSONObject()
            .put("key", key)
            .put("label", label)
            .put("updatedAt", updatedAt)
            .put("messages", JSONArray().also { array -> messages.forEach { array.put(it.toJson()) } })

    private fun LocalChatMessage.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("role", role)
            .put("text", text)
            .put("timestamp", timestamp)
            .put("attachments", ChatAttachmentPreviewJson.toJsonArray(attachments))

    private fun defaultSession(key: String = "local:main"): LocalChatSession =
        LocalChatSession(key = key, label = "Local chat", updatedAt = System.currentTimeMillis(), messages = emptyList())
}
