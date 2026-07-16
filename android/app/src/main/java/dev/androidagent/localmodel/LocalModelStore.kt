package dev.androidagent.localmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.androidagent.storage.AppPrivateBlobStore
import dev.androidagent.storage.BlobImportRequest
import dev.androidagent.storage.BlobStoreLimits
import dev.androidagent.storage.StoredBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

object LocalModelStore {
    private const val LITERTLM_MODEL_DIR = "local-model-blobs"
    private const val GGUF_MODEL_DIR = "local-gguf-model-blobs"
    private const val MIN_MODEL_BYTES = 1024L * 1024L
    private val LITERTLM_LIMITS = modelLimits(
        maxItemBytes = 4L * 1024L * 1024L * 1024L,
        maxAggregateBytes = 8L * 1024L * 1024L * 1024L
    )
    private val GGUF_LIMITS = modelLimits(
        maxItemBytes = 12L * 1024L * 1024L * 1024L,
        maxAggregateBytes = 24L * 1024L * 1024L * 1024L,
        maxBlobCount = 8
    )

    suspend fun importModel(
        context: Context,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst()) return@use null
            ModelMetadata(
                displayName = (if (nameIndex >= 0) cursor.getString(nameIndex) else null)
                    ?: "model-${System.currentTimeMillis()}.litertlm",
                declaredSizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                mimeType = resolver.getType(uri)
            )
        } ?: ModelMetadata(
            displayName = "model-${System.currentTimeMillis()}.litertlm",
            declaredSizeBytes = null,
            mimeType = resolver.getType(uri)
        )
        val format = formatForDisplayName(metadata.displayName)
            ?: throw IllegalArgumentException("Select a .litertlm or .gguf model file")
        importStream(
            directory = File(context.filesDir, format.directoryName),
            metadata = metadata,
            openInput = { resolver.openInputStream(uri) ?: throw IOException("Could not open selected model file") },
            shouldCancel = { !isActive },
            onProgress = onProgress,
            limits = format.limits
        ).file.absolutePath
    }

    internal fun importStream(
        directory: File,
        metadata: ModelMetadata,
        openInput: () -> InputStream,
        shouldCancel: () -> Boolean = { false },
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        limits: BlobStoreLimits? = null
    ): StoredBlob {
        val displayName = metadata.displayName?.trim().orEmpty()
        val format = formatForDisplayName(displayName)
            ?: throw IllegalArgumentException("Select a .litertlm or .gguf model file")
        val store = AppPrivateBlobStore(
            directory = directory,
            limits = limits ?: format.limits,
            payloadSuffix = format.extension
        )
        return store.import(
            request = BlobImportRequest(
                id = "blob_${UUID.randomUUID()}",
                displayName = displayName,
                mimeType = metadata.mimeType ?: "application/octet-stream",
                declaredSizeBytes = metadata.declaredSizeBytes?.takeIf { it > 0L }
            ),
            openInput = openInput,
            shouldCancel = shouldCancel,
            onProgress = onProgress
        )
    }

    internal fun formatForDisplayName(displayName: String?): ModelFormat? {
        val normalized = displayName?.trim().orEmpty()
        return ModelFormat.values().firstOrNull { normalized.endsWith(it.extension, ignoreCase = true) }
    }

    internal fun requireFormatForPath(path: String): ModelFormat =
        formatForDisplayName(path) ?: throw UnsupportedLocalModelPathException(path)

    fun exists(path: String): Boolean =
        formatForDisplayName(path) != null && File(path.trim()).isFile

    fun displayName(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return "No model selected"
        val file = File(trimmed)
        val fallback = file.name.removeSuffix(".litertlm").removeSuffix(".gguf").ifBlank { "Local model" }
        val metadataFile = File(file.parentFile, "${file.nameWithoutExtension}.json")
        val storedDisplayName = runCatching {
            if (metadataFile.isFile) {
                JSONObject(metadataFile.readText()).optString("displayName").takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
        return storedDisplayName ?: fallback
    }

    // Newest first.
    internal fun listImportedModels(context: Context): List<ImportedModel> {
        return ModelFormat.values().flatMap { format ->
            val directory = File(context.filesDir, format.directoryName)
            directory.listFiles().orEmpty()
                .filter { it.name.endsWith(".json") }
                .mapNotNull { metadataFile ->
                    runCatching {
                        val json = JSONObject(metadataFile.readText())
                        val id = json.getString("id")
                        val payloadFile = File(directory, "$id${format.extension}")
                        if (!payloadFile.isFile) return@runCatching null
                        ImportedModel(
                            path = payloadFile.absolutePath,
                            displayName = json.optString("displayName").takeIf { it.isNotBlank() } ?: id,
                            format = format,
                            sizeBytes = json.optLong("sizeBytes", payloadFile.length()),
                            createdAt = json.optLong("createdAt", payloadFile.lastModified())
                        )
                    }.getOrNull()
                }
        }.sortedByDescending { it.createdAt }
    }

    internal data class ImportedModel(
        val path: String,
        val displayName: String,
        val format: ModelFormat,
        val sizeBytes: Long,
        val createdAt: Long
    )

    internal enum class ModelFormat(
        val extension: String,
        val directoryName: String,
        val limits: BlobStoreLimits
    ) {
        LiteRtLm(".litertlm", LITERTLM_MODEL_DIR, LITERTLM_LIMITS),
        Gguf(".gguf", GGUF_MODEL_DIR, GGUF_LIMITS)
    }

    internal data class ModelMetadata(
        val displayName: String?,
        val declaredSizeBytes: Long?,
        val mimeType: String?
    )

    private fun modelLimits(maxItemBytes: Long, maxAggregateBytes: Long, maxBlobCount: Int = 3) = BlobStoreLimits(
        minItemBytes = MIN_MODEL_BYTES,
        maxItemBytes = maxItemBytes,
        maxBlobCount = maxBlobCount,
        maxAggregateBytes = maxAggregateBytes,
        freeSpaceReserveBytes = 512L * 1024L * 1024L,
        retentionMillis = Long.MAX_VALUE
    )
}

internal class UnsupportedLocalModelPathException(path: String) : IllegalArgumentException(
    "Unsupported local model path: $path. Only .litertlm and .gguf models are supported."
)
