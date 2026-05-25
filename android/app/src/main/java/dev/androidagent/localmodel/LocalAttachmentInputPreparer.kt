package dev.androidagent.localmodel

import dev.androidagent.chat.ChatAttachmentPolicy
import dev.androidagent.chat.StoredChatAttachment
import java.io.File

data class PreparedLocalModelInput(
    val promptText: String,
    val imagePaths: List<String>
)

object LocalAttachmentInputPreparer {
    fun prepare(text: String, attachments: List<StoredChatAttachment>): PreparedLocalModelInput {
        if (attachments.isEmpty()) {
            return PreparedLocalModelInput(promptText = text, imagePaths = emptyList())
        }

        val imageAttachments = attachments.filter { it.isImage }
        ChatAttachmentPolicy.validateSingleLocalImage(attachments)
        val fileSections = attachments
            .filterNot { it.isImage }
            .map { attachment -> localTextFileSection(attachment) }
        val prompt = buildString {
            append(text.ifBlank {
                if (imageAttachments.isNotEmpty()) "Describe the attached image." else "Review the attached file."
            })
            fileSections.forEach { section ->
                append("\n\n")
                append(section)
            }
        }
        return PreparedLocalModelInput(
            promptText = prompt,
            imagePaths = imageAttachments.map { it.localPath }
        )
    }

    private fun localTextFileSection(attachment: StoredChatAttachment): String {
        if (!ChatAttachmentPolicy.isSupportedLocalTextAttachment(attachment)) {
            throw IllegalArgumentException("Local mode supports image attachments and small text files only. ${attachment.displayName} is ${attachment.mimeType}.")
        }
        val file = File(attachment.localPath)
        if (!file.isFile) {
            throw IllegalArgumentException("Could not read ${attachment.displayName}.")
        }
        if (file.length() > ChatAttachmentPolicy.LOCAL_TEXT_ATTACHMENT_MAX_BYTES) {
            throw IllegalArgumentException("${attachment.displayName} is too large for local text attachment input.")
        }
        val text = file.readText(Charsets.UTF_8).take(ChatAttachmentPolicy.LOCAL_TEXT_ATTACHMENT_MAX_CHARS)
        return "Attached file (${attachment.displayName}, ${attachment.mimeType}):\n```\n$text\n```"
    }
}
