package dev.androidagent.storage

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BlobStoreLimits(
    val maxItemBytes: Long,
    val maxBlobCount: Int,
    val maxAggregateBytes: Long,
    val freeSpaceReserveBytes: Long,
    val retentionMillis: Long
) {
    init {
        require(maxItemBytes > 0)
        require(maxBlobCount > 0)
        require(maxAggregateBytes >= maxItemBytes)
        require(freeSpaceReserveBytes >= 0)
        require(retentionMillis > 0)
    }
}

data class BlobImportRequest(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val declaredSizeBytes: Long? = null
)

data class StoredBlob(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val file: File
)

class BlobImportCancelledException : IOException("Blob import cancelled")

class AppPrivateBlobStore(
    private val directory: File,
    private val limits: BlobStoreLimits,
    private val payloadSuffix: String = ".blob",
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val usableSpaceBytes: (File) -> Long = File::getUsableSpace
) {
    private val coordinator = coordinators.computeIfAbsent(directory.canonicalPath) { StoreCoordinator() }

    init {
        require(PAYLOAD_SUFFIX.matches(payloadSuffix)) { "Invalid blob payload suffix" }
        directory.mkdirs()
        cleanup()
    }

    fun import(
        request: BlobImportRequest,
        openInput: () -> InputStream,
        shouldCancel: () -> Boolean = { false },
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): StoredBlob {
        val normalized = normalizeRequest(request)
        val reservationBytes = normalized.declaredSizeBytes ?: limits.maxItemBytes
        reserve(normalized.id, reservationBytes)
        val partial = File(directory, ".${normalized.id}-${UUID.randomUUID()}.partial")
        synchronized(coordinator) {
            coordinator.activePartialNames += partial.name
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            openInput().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (shouldCancel()) throw BlobImportCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (copiedBytes > limits.maxItemBytes - read) {
                            throw BlobLimitExceededException("${normalized.displayName} exceeds the ${limits.maxItemBytes}-byte item limit")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read
                        onProgress(copiedBytes, normalized.declaredSizeBytes)
                    }
                    output.flush()
                }
            }
            if (shouldCancel()) throw BlobImportCancelledException()
            normalized.declaredSizeBytes?.let { declared ->
                if (copiedBytes != declared) {
                    throw IOException("${normalized.displayName} size changed while importing (declared $declared bytes, received $copiedBytes)")
                }
            }
            if (copiedBytes <= 0L) throw IOException("${normalized.displayName} is empty")
            val blob = StoredBlob(
                id = normalized.id,
                displayName = normalized.displayName,
                mimeType = normalized.mimeType,
                sizeBytes = copiedBytes,
                sha256 = digest.digest().toHex(),
                file = payloadFile(normalized.id)
            )
            publish(partial, blob)
            return blob
        } finally {
            partial.delete()
            synchronized(coordinator) {
                coordinator.activePartialNames -= partial.name
                coordinator.reservations.remove(normalized.id)
            }
        }
    }

    fun resolve(id: String, expectedSha256: String? = null): StoredBlob? {
        return synchronized(coordinator) {
            if (!BLOB_ID.matches(id)) return@synchronized null
            val metadata = readMetadata(metadataFile(id)) ?: return@synchronized null
            val payload = payloadFile(id)
            if (!payload.isFile || payload.length() != metadata.sizeBytes) return@synchronized null
            if (expectedSha256 != null && !metadata.sha256.equals(expectedSha256, ignoreCase = true)) return@synchronized null
            payload.setLastModified(nowMillis())
            metadata.copy(file = payload)
        }
    }

    fun delete(id: String): Boolean {
        return synchronized(coordinator) {
            if (!BLOB_ID.matches(id) || coordinator.reservations.containsKey(id)) return@synchronized false
            val payloadDeleted = payloadFile(id).delete()
            val metadataDeleted = metadataFile(id).delete()
            payloadDeleted || metadataDeleted
        }
    }

    fun cleanup(): Int {
        return synchronized(coordinator) {
            directory.mkdirs()
            var deleted = 0
            directory.listFiles().orEmpty()
                .filter { it.name.endsWith(".partial") && it.name !in coordinator.activePartialNames }
                .forEach { if (it.delete()) deleted += 1 }

            val cutoff = nowMillis() - limits.retentionMillis
            val metadataFiles = directory.listFiles().orEmpty().filter { it.name.endsWith(METADATA_SUFFIX) }
            val knownPayloadNames = mutableSetOf<String>()
            metadataFiles.forEach { metadataFile ->
                val metadata = readMetadata(metadataFile)
                val payload = metadata?.let { payloadFile(it.id) }
                if (metadata == null || payload == null || !payload.isFile || payload.length() != metadata.sizeBytes) {
                    if (metadataFile.delete()) deleted += 1
                    if (payload?.delete() == true) deleted += 1
                    return@forEach
                }
                knownPayloadNames += payload.name
                if (payload.lastModified() < cutoff && !coordinator.reservations.containsKey(metadata.id)) {
                    if (payload.delete()) deleted += 1
                    if (metadataFile.delete()) deleted += 1
                    knownPayloadNames -= payload.name
                }
            }
            directory.listFiles().orEmpty()
                .filter { it.name.endsWith(payloadSuffix) && it.name !in knownPayloadNames }
                .forEach { if (it.delete()) deleted += 1 }
            deleted
        }
    }

    private fun reserve(id: String, reservationBytes: Long) {
        synchronized(coordinator) {
            require(BLOB_ID.matches(id)) { "Invalid blob id" }
            if (coordinator.reservations.containsKey(id) || payloadFile(id).exists() || metadataFile(id).exists()) {
                throw IOException("Blob $id already exists")
            }
            val published = publishedPayloads()
            if (published.size + coordinator.reservations.size >= limits.maxBlobCount) {
                throw BlobLimitExceededException("Blob store contains the maximum of ${limits.maxBlobCount} items")
            }
            val committedBytes = published.sumOf(File::length)
            val reservedBytes = coordinator.reservations.values.sum()
            if (committedBytes > limits.maxAggregateBytes - reservedBytes - reservationBytes) {
                throw BlobLimitExceededException("Blob store exceeds its ${limits.maxAggregateBytes}-byte aggregate limit")
            }
            val usable = usableSpaceBytes(directory)
            if (usable < limits.freeSpaceReserveBytes + reservationBytes) {
                throw BlobLimitExceededException("Not enough free storage to import blob while preserving the free-space reserve")
            }
            coordinator.reservations[id] = reservationBytes
        }
    }

    private fun publish(partial: File, blob: StoredBlob) {
        synchronized(coordinator) {
            val target = blob.file
            val metadata = metadataFile(blob.id)
            val metadataPartial = File(directory, ".${blob.id}-${UUID.randomUUID()}.metadata.partial")
            try {
                if (target.exists() || metadata.exists()) throw IOException("Blob ${blob.id} already exists")
                if (!partial.renameTo(target)) throw IOException("Could not atomically publish ${blob.displayName}")
                target.setLastModified(nowMillis())
                metadataPartial.writeText(blob.toMetadataJson(nowMillis()).toString())
                if (!metadataPartial.renameTo(metadata)) {
                    target.delete()
                    throw IOException("Could not publish metadata for ${blob.displayName}")
                }
            } finally {
                metadataPartial.delete()
            }
        }
    }

    private fun normalizeRequest(request: BlobImportRequest): BlobImportRequest {
        require(BLOB_ID.matches(request.id)) { "Invalid blob id" }
        val displayName = request.displayName
            .replace(CONTROL_CHARACTERS, "")
            .trim()
            .take(MAX_DISPLAY_NAME_CHARS)
            .ifBlank { "blob" }
        val mimeType = request.mimeType.trim().lowercase().takeIf(MIME_TYPE::matches)
            ?: "application/octet-stream"
        val declared = request.declaredSizeBytes?.takeIf { it >= 0L }
        if (declared != null && declared > limits.maxItemBytes) {
            throw BlobLimitExceededException("$displayName exceeds the ${limits.maxItemBytes}-byte item limit")
        }
        return request.copy(displayName = displayName, mimeType = mimeType, declaredSizeBytes = declared)
    }

    private fun publishedPayloads(): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(payloadSuffix) }

    private fun payloadFile(id: String): File = File(directory, "$id$payloadSuffix")
    private fun metadataFile(id: String): File = File(directory, "$id$METADATA_SUFFIX")

    private fun readMetadata(file: File): StoredBlob? {
        if (!file.isFile || file.length() > MAX_METADATA_BYTES) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val id = json.getString("id")
            val displayName = json.getString("displayName")
            val mimeType = json.getString("mimeType")
            val sizeBytes = json.getLong("sizeBytes")
            val sha256 = json.getString("sha256")
            if (!BLOB_ID.matches(id) || sizeBytes <= 0L || !SHA256.matches(sha256)) return null
            StoredBlob(id, displayName, mimeType, sizeBytes, sha256, payloadFile(id))
        }.getOrNull()
    }

    private fun StoredBlob.toMetadataJson(createdAt: Long): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)
            .put("sha256", sha256)
            .put("createdAt", createdAt)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_DISPLAY_NAME_CHARS = 120
        private const val MAX_METADATA_BYTES = 16 * 1024L
        private const val METADATA_SUFFIX = ".json"
        private val BLOB_ID = Regex("^blob_[a-zA-Z0-9-]{8,80}$")
        private val PAYLOAD_SUFFIX = Regex("^\\.[a-zA-Z0-9]{1,16}$")
        private val MIME_TYPE = Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")
        private val SHA256 = Regex("^[a-fA-F0-9]{64}$")
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f]")
        private val coordinators = ConcurrentHashMap<String, StoreCoordinator>()
    }

    private class StoreCoordinator {
        val reservations = mutableMapOf<String, Long>()
        val activePartialNames = mutableSetOf<String>()
    }
}

class BlobLimitExceededException(message: String) : IOException(message)
