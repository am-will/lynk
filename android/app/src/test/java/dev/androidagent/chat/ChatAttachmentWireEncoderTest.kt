package dev.androidagent.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChatAttachmentWireEncoderTest {
    @Test
    fun encodesStoredAttachmentWithoutLocalPath() {
        val file = File.createTempFile("chat-attachment", ".txt").apply {
            writeText("hello")
            deleteOnExit()
        }
        val attachment = StoredChatAttachment(
            id = "att_1",
            kind = ChatAttachmentKind.FILE,
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            localPath = file.absolutePath
        )

        val encoded = ChatAttachmentWireEncoder.toJsonArray(listOf(attachment)).getJSONObject(0)

        assertEquals("att_1", encoded.getString("id"))
        assertEquals("file", encoded.getString("kind"))
        assertEquals("aGVsbG8=", encoded.getString("contentBase64"))
        assertEquals(false, encoded.has("localPath"))
    }
}
