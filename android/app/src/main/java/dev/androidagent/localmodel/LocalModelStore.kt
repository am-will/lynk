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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

object LocalModelStore {
    private const val MODEL_DIR = "local-model-blobs"
    private const val MIN_MODEL_BYTES = 1024L * 1024L
    private const val MAX_MODEL_BYTES = 4L * 1024L * 1024L * 1024L
    private val MODEL_LIMITS = BlobStoreLimits(
        minItemBytes = MIN_MODEL_BYTES,
        maxItemBytes = MAX_MODEL_BYTES,
        maxBlobCount = 3,
        maxAggregateBytes = 8L * 1024L * 1024L * 1024L,
        freeSpaceReserveBytes = 512L * 1024L * 1024L,
        retentionMillis = Long.MAX_VALUE
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
        importStream(
            directory = File(context.filesDir, MODEL_DIR),
            metadata = metadata,
            openInput = { resolver.openInputStream(uri) ?: throw IOException("Could not open selected model file") },
            shouldCancel = { !isActive },
            onProgress = onProgress
        ).file.absolutePath
    }

    internal fun importStream(
        directory: File,
        metadata: ModelMetadata,
        openInput: () -> InputStream,
        shouldCancel: () -> Boolean = { false },
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        limits: BlobStoreLimits = MODEL_LIMITS
    ): StoredBlob {
        val displayName = metadata.displayName?.trim().orEmpty()
        if (!displayName.endsWith(".litertlm", ignoreCase = true)) {
            throw IllegalArgumentException("Select a .litertlm model file")
        }
        val store = AppPrivateBlobStore(
            directory = directory,
            limits = limits,
            payloadSuffix = ".litertlm"
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

    fun exists(path: String): Boolean = path.isNotBlank() && File(path).isFile

    internal data class ModelMetadata(
        val displayName: String?,
        val declaredSizeBytes: Long?,
        val mimeType: String?
    )
}
