package dev.androidagent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ChatAttachmentWireEncoderTest {
    @Test
    fun encodesReferenceMetadataWithoutLocalPathOrPayload() {
        val file = File.createTempFile("chat-attachment", ".txt").apply {
            writeText("hello")
            deleteOnExit()
        }
        val attachment = StoredChatAttachment(
            id = "blob_attachment1",
            kind = ChatAttachmentKind.FILE,
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            sha256 = "a".repeat(64)
        )

        val encoded = ChatAttachmentWireEncoder.toJsonArray(listOf(attachment)).getJSONObject(0)

        assertEquals("blob_attachment1", encoded.getString("id"))
        assertEquals("file", encoded.getString("kind"))
        assertEquals("a".repeat(64), encoded.getString("sha256"))
        assertFalse(encoded.has("contentBase64"))
        assertFalse(encoded.has("localPath"))
    }
}
