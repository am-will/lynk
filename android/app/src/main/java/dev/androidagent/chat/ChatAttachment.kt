package dev.androidagent.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ChatAttachmentKind(val wireValue: String) {
    IMAGE("image"),
    FILE("file");

    companion object {
        fun fromWireValue(value: String?): ChatAttachmentKind {
            return entries.firstOrNull { it.wireValue == value } ?: FILE
        }
    }
}

data class ChatAttachment(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String
) {
    val isImage: Boolean
        get() = kind == ChatAttachmentKind.IMAGE || mimeType.startsWith("image/")

    fun toJson(includeContent: Boolean = false, includeLocalPath: Boolean = true): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("kind", kind.wireValue)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)
        if (includeLocalPath) {
            json.put("localPath", localPath)
        }
        if (includeContent) {
            json.put("contentBase64", Base64.encodeToString(File(localPath).readBytes(), Base64.NO_WRAP))
        }
        return json
    }

    companion object {
        fun fromJson(value: JSONObject?): ChatAttachment? {
            value ?: return null
            val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
            val displayName = value.optString("displayName").takeIf { it.isNotBlank() } ?: "Attachment"
            val mimeType = value.optString("mimeType").takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val localPath = value.optString("localPath").takeIf { it.isNotBlank() } ?: ""
            return ChatAttachment(
                id = id,
                kind = ChatAttachmentKind.fromWireValue(value.optString("kind")),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = value.optLong("sizeBytes", 0L),
                localPath = localPath
            )
        }
    }
}

object ChatAttachmentJson {
    fun toJsonArray(
        attachments: List<ChatAttachment>,
        includeContent: Boolean = false,
        includeLocalPath: Boolean = true
    ): JSONArray {
        return JSONArray().also { array ->
            attachments.forEach { attachment ->
                array.put(attachment.toJson(includeContent = includeContent, includeLocalPath = includeLocalPath))
            }
        }
    }

    fun fromJsonArray(array: JSONArray?): List<ChatAttachment> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                ChatAttachment.fromJson(array.optJSONObject(index))?.let { add(it) }
            }
        }
    }
}

class ChatAttachmentStore(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "chat-attachments")

    fun importUri(uri: Uri, requestedKind: ChatAttachmentKind): ChatAttachment {
        val resolver = context.contentResolver
        val metadata = queryMetadata(uri)
        val mimeType = resolver.getType(uri)
            ?: metadata.mimeType
            ?: if (requestedKind == ChatAttachmentKind.IMAGE) "image/*" else "application/octet-stream"
        val displayName = metadata.displayName ?: fallbackDisplayName(requestedKind, mimeType)
        val id = "att_${UUID.randomUUID()}"
        val outputFile = File(directory, "$id-${sanitizeFileName(displayName)}")
        directory.mkdirs()
        resolver.openInputStream(uri)?.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Could not open selected file")
        val sizeBytes = metadata.sizeBytes?.takeIf { it >= 0L } ?: outputFile.length()
        return ChatAttachment(
            id = id,
            kind = if (mimeType.startsWith("image/")) ChatAttachmentKind.IMAGE else requestedKind,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localPath = outputFile.absolutePath
        )
    }

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        val resolver = context.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                return AttachmentMetadata(displayName = name, sizeBytes = size, mimeType = resolver.getType(uri))
            }
        }
        return AttachmentMetadata(displayName = null, sizeBytes = null, mimeType = resolver.getType(uri))
    }

    private fun fallbackDisplayName(kind: ChatAttachmentKind, mimeType: String): String {
        val extension = mimeType.substringAfter('/', "").takeIf { it.isNotBlank() && "*" !in it }
        val baseName = if (kind == ChatAttachmentKind.IMAGE) "image" else "file"
        return extension?.let { "$baseName.$it" } ?: baseName
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[^\w. -]"""), "_").take(80).ifBlank { "attachment" }
    }

    private data class AttachmentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
        val mimeType: String?
    )
}
