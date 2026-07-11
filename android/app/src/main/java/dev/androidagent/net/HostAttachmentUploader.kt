package dev.androidagent.net

import dev.androidagent.BridgeEndpointPolicy
import dev.androidagent.chat.StoredChatAttachment
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HostAttachmentUploader(private val client: OkHttpClient) {
    suspend fun upload(
        hostUrl: String,
        allowInsecureTrustedOverlay: Boolean,
        token: String,
        deviceId: String,
        sessionKey: String,
        attachment: StoredChatAttachment,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val request = buildRequest(
            hostUrl = hostUrl,
            allowInsecureTrustedOverlay = allowInsecureTrustedOverlay,
            token = token,
            deviceId = deviceId,
            sessionKey = sessionKey,
            attachment = attachment,
            onProgress = onProgress
        )
        executeAndValidate(request, attachment)
    }

    private suspend fun executeAndValidate(request: Request, attachment: StoredChatAttachment) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { validateResponse(it, attachment) }
                    }
                    result.fold(
                        onSuccess = {
                            if (continuation.isActive) continuation.resume(Unit)
                        },
                        onFailure = { error ->
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    )
                }
            })
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 16 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024

        internal fun buildRequest(
            hostUrl: String,
            allowInsecureTrustedOverlay: Boolean,
            token: String,
            deviceId: String,
            sessionKey: String,
            attachment: StoredChatAttachment,
            onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
        ): Request {
            require(token.isNotBlank()) { "Bridge token is missing. Re-pair the app." }
            require(deviceId.isNotBlank()) { "Device id is missing." }
            require(sessionKey.isNotBlank()) { "Select a chat session before sending an attachment." }
            val file = File(attachment.localPath)
            require(file.isFile && file.canRead()) { "${attachment.displayName} is no longer available. Reattach the file." }
            require(file.length() == attachment.sizeBytes) { "${attachment.displayName} changed after it was attached. Reattach the file." }

            val url = blobUrl(hostUrl, allowInsecureTrustedOverlay, attachment)
            return Request.Builder()
                .url(url)
                .put(StreamingFileRequestBody(file, attachment.mimeType.toMediaTypeOrNull(), onProgress))
                .header("Authorization", "Bearer $token")
                .header("X-Lynk-Device-Id", deviceId)
                .header("X-Lynk-Session-Key", sessionKey)
                .build()
        }

        internal fun blobUrl(
            hostUrl: String,
            allowInsecureTrustedOverlay: Boolean,
            attachment: StoredChatAttachment
        ): String {
            val endpoint = BridgeEndpointPolicy.normalize(hostUrl, allowInsecureTrustedOverlay)
                ?: throw IllegalArgumentException("Bridge endpoint is not allowed for attachment uploads.")
            val websocketUri = URI(endpoint.url)
            val httpScheme = if (websocketUri.scheme == "wss") "https" else "http"
            val baseUrl = URI(httpScheme, null, websocketUri.host, websocketUri.port, "/", null, null)
                .toString()
                .toHttpUrl()
            return baseUrl.newBuilder()
                .addPathSegments("api/blobs")
                .addPathSegment(attachment.id)
                .addQueryParameter("displayName", attachment.displayName)
                .addQueryParameter("mimeType", attachment.mimeType)
                .addQueryParameter("kind", attachment.kind.wireValue)
                .addQueryParameter("sha256", attachment.sha256)
                .build()
                .toString()
        }

        private fun validateResponse(response: Response, attachment: StoredChatAttachment) {
            val body = readBoundedBody(response)
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: body.take(240).takeIf { it.isNotBlank() }
                    ?: response.message
                throw IOException("Attachment upload failed (${response.code}): $detail")
            }
            val blob = runCatching { JSONObject(body).getJSONObject("blob") }
                .getOrElse { throw IOException("Bridge returned an invalid attachment upload response", it) }
            if (blob.optString("id") != attachment.id
                || blob.optString("sha256") != attachment.sha256
                || blob.optLong("sizeBytes", -1L) != attachment.sizeBytes) {
                throw IOException("Bridge returned mismatched metadata for ${attachment.displayName}")
            }
        }

        private fun readBoundedBody(response: Response): String {
            val body = response.body ?: return ""
            body.byteStream().use { input ->
                val bytes = ByteArray(MAX_RESPONSE_BYTES + 1)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    if (read == 0) continue
                    offset += read
                }
                if (offset > MAX_RESPONSE_BYTES) throw IOException("Bridge attachment response exceeded $MAX_RESPONSE_BYTES bytes")
                return bytes.decodeToString(0, offset)
            }
        }

        private class StreamingFileRequestBody(
            private val file: File,
            private val mediaType: MediaType?,
            private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
        ) : RequestBody() {
            private val expectedBytes = file.length()

            override fun contentType(): MediaType? = mediaType

            override fun contentLength(): Long = expectedBytes

            override fun writeTo(sink: BufferedSink) {
                var sentBytes = 0L
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        sentBytes += read
                        onProgress(sentBytes, expectedBytes)
                    }
                }
                if (sentBytes != expectedBytes) {
                    throw IOException("${file.name} changed while it was uploading")
                }
            }
        }
    }
}
