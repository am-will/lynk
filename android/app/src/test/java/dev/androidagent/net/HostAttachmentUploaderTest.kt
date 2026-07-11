package dev.androidagent.net

import dev.androidagent.chat.ChatAttachmentKind
import dev.androidagent.chat.StoredChatAttachment
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class HostAttachmentUploaderTest {
    @Test
    fun buildsAuthenticatedStreamingUploadWithoutEmbeddingFileBytesInUrl() {
        val attachment = attachment("hello")
        val request = HostAttachmentUploader.buildRequest(
            hostUrl = "ws://127.0.0.1:8788/phone",
            allowInsecureTrustedOverlay = false,
            token = "secret-token",
            deviceId = "pixel-1",
            sessionKey = "codex:session-1",
            attachment = attachment
        )
        val body = requireNotNull(request.body)
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals("PUT", request.method)
        assertEquals("http", request.url.scheme)
        assertEquals("/api/blobs/${attachment.id}", request.url.encodedPath)
        assertEquals(attachment.displayName, request.url.queryParameter("displayName"))
        assertEquals(attachment.sha256, request.url.queryParameter("sha256"))
        assertEquals("Bearer secret-token", request.header("Authorization"))
        assertEquals("pixel-1", request.header("X-Lynk-Device-Id"))
        assertEquals("codex:session-1", request.header("X-Lynk-Session-Key"))
        assertEquals(attachment.sizeBytes, body.contentLength())
        assertEquals("hello", sink.readUtf8())
        assertFalse(request.url.toString().contains("hello"))
    }

    @Test
    fun mapsSecureWebSocketEndpointsToHttpsAndEncodesMetadata() {
        val attachment = attachment("data").copy(displayName = "photo one.png")
        val url = HostAttachmentUploader.blobUrl(
            hostUrl = "wss://bridge.example.com/phone",
            allowInsecureTrustedOverlay = false,
            attachment = attachment
        ).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("bridge.example.com", url.host)
        assertEquals("photo one.png", url.queryParameter("displayName"))
    }

    @Test
    fun streamingBodyDetectsFileChangesAfterRequestCreation() {
        val attachment = attachment("hello")
        val request = HostAttachmentUploader.buildRequest(
            hostUrl = "ws://127.0.0.1:8788/phone",
            allowInsecureTrustedOverlay = false,
            token = "secret-token",
            deviceId = "pixel-1",
            sessionKey = "codex:session-1",
            attachment = attachment
        )
        File(attachment.localPath).appendText(" changed")

        assertThrows(IOException::class.java) {
            requireNotNull(request.body).writeTo(Buffer())
        }
    }

    @Test
    fun uploadStreamsTheFileAndValidatesBridgeMetadata() {
        val attachment = attachment("stream me")
        val uploaded = Buffer()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requireNotNull(chain.request().body).writeTo(uploaded)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"ok":true,"blob":{"id":"${attachment.id}","sizeBytes":${attachment.sizeBytes},"sha256":"${attachment.sha256}"}}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        try {
            runBlocking {
                HostAttachmentUploader(client).upload(
                    hostUrl = "ws://127.0.0.1:8788/phone",
                    allowInsecureTrustedOverlay = false,
                    token = "secret-token",
                    deviceId = "pixel-1",
                    sessionKey = "codex:session-1",
                    attachment = attachment
                )
            }
            assertEquals("stream me", uploaded.readUtf8())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun attachment(content: String): StoredChatAttachment {
        val file = File.createTempFile("host-attachment", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }
        return StoredChatAttachment(
            id = "blob_attachment-test",
            kind = ChatAttachmentKind.FILE,
            displayName = "note.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            sha256 = "a".repeat(64)
        )
    }
}
