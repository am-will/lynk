package dev.androidagent.chat

import java.io.File

object ChatAttachmentPolicy {
    const val MAX_ATTACHMENT_BYTES: Long = 50L * 1024L * 1024L
    const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 8
    const val MAX_MESSAGE_ATTACHMENT_BYTES: Long = 100L * 1024L * 1024L
    const val LOCAL_TEXT_ATTACHMENT_MAX_BYTES: Long = 64L * 1024L
    const val LOCAL_TEXT_ATTACHMENT_MAX_CHARS: Int = 32_000

    private val localTextAttachmentExtensions = setOf(
        ".txt",
        ".md",
        ".json",
        ".csv",
        ".log",
        ".xml",
        ".html",
        ".kt",
        ".java",
        ".js",
        ".ts",
        ".py"
    )

    fun validateImport(sizeBytes: Long, displayName: String) {
        if (sizeBytes > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("$displayName is too large to attach. Maximum size is ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB.")
        }
    }

    fun validateHostSend(attachments: List<StoredChatAttachment>) {
        if (attachments.size > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw IllegalArgumentException("Attach at most $MAX_ATTACHMENTS_PER_MESSAGE files per message.")
        }
        var aggregateBytes = 0L
        attachments.forEach { attachment ->
            val file = File(attachment.localPath)
            require(BLOB_ID.matches(attachment.id)) { "${attachment.displayName} has an invalid attachment id." }
            require(SHA256.matches(attachment.sha256)) { "${attachment.displayName} has no valid checksum. Reattach the file." }
            require(file.isFile && file.canRead()) { "${attachment.displayName} is no longer available. Reattach the file." }
            val size = file.length()
            require(size > 0L) { "${attachment.displayName} is empty and cannot be sent." }
            require(size == attachment.sizeBytes) { "${attachment.displayName} changed after it was attached. Reattach the file." }
            validateImport(size, attachment.displayName)
            if (aggregateBytes > MAX_MESSAGE_ATTACHMENT_BYTES - size) {
                throw IllegalArgumentException("Attachments exceed the ${MAX_MESSAGE_ATTACHMENT_BYTES / (1024 * 1024)} MB message limit.")
            }
            aggregateBytes += size
        }
    }

    fun validateSingleLocalImage(attachments: List<StoredChatAttachment>) {
        if (attachments.count { it.isImage } > 1) {
            throw IllegalArgumentException("Local LiteRT-LM supports one image attachment at a time.")
        }
    }

    fun isSupportedLocalTextAttachment(attachment: StoredChatAttachment): Boolean {
        if (attachment.mimeType.startsWith("text/")) return true
        val name = attachment.displayName.lowercase()
        return localTextAttachmentExtensions.any { name.endsWith(it) }
    }

    private val BLOB_ID = Regex("^blob_[a-zA-Z0-9-]{8,80}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
}
