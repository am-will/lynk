package dev.androidagent.localmodel

import dev.androidagent.chat.ChatAttachmentKind
import dev.androidagent.chat.StoredChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalAttachmentInputPreparerTest {
    @Test
    fun inlinesSmallTextFilesIntoPrompt() {
        val file = File.createTempFile("local-attachment", ".txt").apply {
            writeText("important notes")
            deleteOnExit()
        }

        val prepared = LocalAttachmentInputPreparer.prepare("Review this", listOf(textAttachment(file)))

        assertTrue(prepared.promptText.startsWith("Review this"))
        assertTrue(prepared.promptText.contains("Attached file (notes.txt, text/plain):"))
        assertTrue(prepared.promptText.contains("important notes"))
        assertEquals(emptyList<String>(), prepared.imagePaths)
    }

    @Test
    fun forwardsSingleImagePathWithDefaultPrompt() {
        val image = StoredChatAttachment(
            id = "att_1",
            kind = ChatAttachmentKind.IMAGE,
            displayName = "photo.png",
            mimeType = "image/png",
            sizeBytes = 12,
            localPath = "/tmp/photo.png"
        )

        val prepared = LocalAttachmentInputPreparer.prepare("", listOf(image))

        assertEquals("Describe the attached image.", prepared.promptText)
        assertEquals(listOf("/tmp/photo.png"), prepared.imagePaths)
    }

    @Test
    fun rejectsUnsupportedFiles() {
        val file = File.createTempFile("local-attachment", ".bin").apply {
            writeText("binary-ish")
            deleteOnExit()
        }

        assertThrows(IllegalArgumentException::class.java) {
            LocalAttachmentInputPreparer.prepare("Review", listOf(StoredChatAttachment(
                id = "att_1",
                kind = ChatAttachmentKind.FILE,
                displayName = "data.bin",
                mimeType = "application/octet-stream",
                sizeBytes = file.length(),
                localPath = file.absolutePath
            )))
        }
    }

    private fun textAttachment(file: File): StoredChatAttachment =
        StoredChatAttachment(
            id = "att_1",
            kind = ChatAttachmentKind.FILE,
            displayName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            localPath = file.absolutePath
        )
}
