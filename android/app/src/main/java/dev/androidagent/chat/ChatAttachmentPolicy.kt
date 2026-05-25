package dev.androidagent.chat

import java.io.File

object ChatAttachmentPolicy {
    const val MAX_ATTACHMENT_BYTES: Long = 5L * 1024L * 1024L
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
        attachments.forEach { attachment ->
            validateImport(File(attachment.localPath).length().takeIf { it > 0L } ?: attachment.sizeBytes, attachment.displayName)
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
}
