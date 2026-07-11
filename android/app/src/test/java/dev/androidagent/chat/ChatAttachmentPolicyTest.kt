package dev.androidagent.chat

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ChatAttachmentPolicyTest {
    @Test
    fun importAcceptsExactItemLimit() {
        ChatAttachmentPolicy.validateImport(
            ChatAttachmentPolicy.MAX_ATTACHMENT_BYTES,
            "exact-limit.bin"
        )
    }

    @Test
    fun hostSendRejectsAttachmentsOverLimit() {
        val file = File.createTempFile("large-chat-attachment", ".bin").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { it.setLength(ChatAttachmentPolicy.MAX_ATTACHMENT_BYTES + 1) }
        val attachment = StoredChatAttachment(
            id = "blob_attachment1",
            kind = ChatAttachmentKind.FILE,
            displayName = "large.bin",
            mimeType = "application/octet-stream",
            sizeBytes = ChatAttachmentPolicy.MAX_ATTACHMENT_BYTES + 1,
            localPath = file.absolutePath,
            sha256 = "a".repeat(64)
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateHostSend(listOf(attachment))
        }
    }

    @Test
    fun hostSendRejectsMissingChecksumsAndChangedFiles() {
        val file = File.createTempFile("changed-chat-attachment", ".bin").apply {
            writeText("hello")
            deleteOnExit()
        }
        val attachment = StoredChatAttachment(
            id = "blob_attachment2",
            kind = ChatAttachmentKind.FILE,
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            localPath = file.absolutePath
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateHostSend(listOf(attachment))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateHostSend(listOf(attachment.copy(sha256 = "a".repeat(64), sizeBytes = 1L)))
        }
    }

    @Test
    fun localModeRejectsMultipleImages() {
        val first = imageAttachment("att_1")
        val second = imageAttachment("att_2")

        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateSingleLocalImage(listOf(first, second))
        }
    }

    private fun imageAttachment(id: String): StoredChatAttachment =
        StoredChatAttachment(
            id = id,
            kind = ChatAttachmentKind.IMAGE,
            displayName = "$id.png",
            mimeType = "image/png",
            sizeBytes = 12,
            localPath = "/tmp/$id.png"
        )
}
