package dev.androidagent.chat

import org.junit.Assert.assertThrows
import org.junit.Test

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
        val attachment = StoredChatAttachment(
            id = "att_1",
            kind = ChatAttachmentKind.FILE,
            displayName = "large.bin",
            mimeType = "application/octet-stream",
            sizeBytes = ChatAttachmentPolicy.MAX_ATTACHMENT_BYTES + 1,
            localPath = "/missing/large.bin"
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateHostSend(listOf(attachment))
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
