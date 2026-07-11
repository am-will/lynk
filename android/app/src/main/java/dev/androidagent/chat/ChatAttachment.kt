package dev.androidagent.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.androidagent.storage.AppPrivateBlobStore
import dev.androidagent.storage.BlobImportRequest
import dev.androidagent.storage.BlobStoreLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

data class StoredChatAttachment(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val sha256: String = ""
) {
    val isImage: Boolean
        get() = kind == ChatAttachmentKind.IMAGE || mimeType.startsWith("image/")

    fun preview(): ChatAttachmentPreview =
        ChatAttachmentPreview(
            id = id,
            kind = kind,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes
        )

    fun toStoredJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.wireValue)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)
            .put("localPath", localPath)
            .put("sha256", sha256)

    companion object {
        fun fromStoredJson(value: JSONObject?): StoredChatAttachment? {
            value ?: return null
            val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
            val displayName = value.optString("displayName").takeIf { it.isNotBlank() } ?: "Attachment"
            val mimeType = value.optString("mimeType").takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val localPath = value.optString("localPath").takeIf { it.isNotBlank() } ?: return null
            return StoredChatAttachment(
                id = id,
                kind = ChatAttachmentKind.fromWireValue(value.optString("kind")),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = value.optLong("sizeBytes", 0L),
                localPath = localPath,
                sha256 = value.optString("sha256")
            )
        }
    }
}

data class ChatAttachmentPreview(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    val isImage: Boolean
        get() = kind == ChatAttachmentKind.IMAGE || mimeType.startsWith("image/")

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.wireValue)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(value: JSONObject?): ChatAttachmentPreview? {
            value ?: return null
            val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
            val displayName = value.optString("displayName").takeIf { it.isNotBlank() } ?: "Attachment"
            val mimeType = value.optString("mimeType").takeIf { it.isNotBlank() } ?: "application/octet-stream"
            return ChatAttachmentPreview(
                id = id,
                kind = ChatAttachmentKind.fromWireValue(value.optString("kind")),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = value.optLong("sizeBytes", 0L)
            )
        }
    }
}

object ChatAttachmentPreviewJson {
    fun toJsonArray(attachments: List<ChatAttachmentPreview>): JSONArray {
        return JSONArray().also { array ->
            attachments.forEach { attachment ->
                array.put(attachment.toJson())
            }
        }
    }

    fun fromJsonArray(array: JSONArray?): List<ChatAttachmentPreview> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                ChatAttachmentPreview.fromJson(array.optJSONObject(index))?.let { add(it) }
            }
        }
    }
}

class ChatAttachmentStore(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "chat-attachments")

    private val blobStore by lazy {
        AppPrivateBlobStore(
            directory = directory,
            limits = BlobStoreLimits(
                maxItemBytes = ChatAttachmentPolicy.MAX_ATTACHMENT_BYTES,
                maxBlobCount = 32,
                maxAggregateBytes = 256L * 1024L * 1024L,
                freeSpaceReserveBytes = 128L * 1024L * 1024L,
                retentionMillis = 7L * 24L * 60L * 60L * 1000L
            )
        )
    }

    suspend fun importUri(
        uri: Uri,
        requestedKind: ChatAttachmentKind,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): StoredChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = queryMetadata(uri)
        val mimeType = resolver.getType(uri)
            ?: metadata.mimeType
            ?: if (requestedKind == ChatAttachmentKind.IMAGE) "image/*" else "application/octet-stream"
        val displayName = metadata.displayName ?: fallbackDisplayName(requestedKind, mimeType)
        val id = "blob_${UUID.randomUUID()}"
        val blob = blobStore.import(
            request = BlobImportRequest(
                id = id,
                displayName = displayName,
                mimeType = mimeType,
                declaredSizeBytes = metadata.sizeBytes?.takeIf { it > 0L }
            ),
            openInput = {
                resolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open selected file")
            },
            shouldCancel = { !isActive },
            onProgress = onProgress
        )
        StoredChatAttachment(
            id = id,
            kind = if (mimeType.startsWith("image/")) ChatAttachmentKind.IMAGE else requestedKind,
            displayName = blob.displayName,
            mimeType = blob.mimeType,
            sizeBytes = blob.sizeBytes,
            localPath = blob.file.absolutePath,
            sha256 = blob.sha256
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

    private data class AttachmentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
        val mimeType: String?
    )
}
