package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object MusicFileExporter {
    private const val TAG = "MusicFileExporter"
    private const val SUBFOLDER = "Metrolist"

    fun exportToMusicFolder(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        mimeType: String,
        cache: SimpleCache,
    ) {
        try {
            val extension = mimeTypeToExtension(mimeType)
            val sanitizedName = sanitizeFilename("$artist - $title")
            val filename = "$sanitizedName$extension"

            val spans = cache.getCachedSpans(songId)
            if (spans.isEmpty()) {
                Log.w(TAG, "No cached spans found for $songId")
                return
            }

            val sortedSpans = spans.sortedBy { it.position }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, filename, mimeType, sortedSpans)
            } else {
                exportViaDirectFile(filename, sortedSpans)
            }

            Log.i(TAG, "Exported '$filename' to Music/$SUBFOLDER/")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export song $songId", e)
        }
    }

    private fun exportViaMediaStore(
        context: Context,
        filename: String,
        mimeType: String,
        spans: List<androidx.media3.datasource.cache.CacheSpan>,
    ) {
        val resolver = context.contentResolver

        // Check for existing file and delete it to allow overwrite
        val existingUri = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(filename, "Music/$SUBFOLDER/"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
            } else {
                null
            }
        }

        if (existingUri != null) {
            resolver.delete(existingUri, null, null)
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$SUBFOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert returned null URI")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                writeSpansToStream(spans, outputStream)
            } ?: throw Exception("Failed to open output stream for $uri")

            val updateValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun exportViaDirectFile(
        filename: String,
        spans: List<androidx.media3.datasource.cache.CacheSpan>,
    ) {
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            SUBFOLDER,
        )
        musicDir.mkdirs()

        val file = File(musicDir, filename)
        FileOutputStream(file).use { outputStream ->
            writeSpansToStream(spans, outputStream)
        }
    }

    private fun writeSpansToStream(
        spans: List<androidx.media3.datasource.cache.CacheSpan>,
        outputStream: OutputStream,
    ) {
        for (span in spans) {
            span.file?.inputStream()?.use { input ->
                input.copyTo(outputStream)
            }
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String =
        when (mimeType) {
            "audio/mp4" -> ".m4a"
            "audio/webm" -> ".webm"
            "audio/ogg" -> ".ogg"
            "audio/mpeg" -> ".mp3"
            "audio/opus" -> ".opus"
            "audio/flac" -> ".flac"
            else -> ".m4a"
        }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(200)
    }
}
