package com.jarvis.assistant.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

object ChatFileSaver {
    @Throws(IOException::class)
    fun save(context: Context, artifact: ChatMessageFormatter.FileArtifact): Uri? {
        val mime = mimeType(artifact.fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Jarvis")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(artifact.content) }
                    ?: throw IOException("Dosya yazma akışı açılamadı")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val targetDir = File(dir, "Jarvis").apply { mkdirs() }
        val file = File(targetDir, artifact.fileName)
        file.writeText(artifact.content, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension in listOf("kt", "java", "py", "js", "ts", "css", "html", "xml", "json", "sql", "sh", "gradle")) "text/plain" else "application/octet-stream"
    }
}
