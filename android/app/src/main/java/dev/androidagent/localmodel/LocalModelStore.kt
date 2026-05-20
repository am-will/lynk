package dev.androidagent.localmodel

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

object LocalModelStore {
    private const val MODEL_DIR = "local-models"

    fun importModel(context: Context, uri: Uri): String {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "model-${System.currentTimeMillis()}.litertlm"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val target = File(dir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Could not open selected model file")
        return target.absolutePath
    }

    fun exists(path: String): Boolean = path.isNotBlank() && File(path).isFile
}
