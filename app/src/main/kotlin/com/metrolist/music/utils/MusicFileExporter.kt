package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

@UnstableApi
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

            Log.d(TAG, "Exporting songId=$songId, filename=$filename, mimeType=$mimeType")

            val contentLength = cache.getCachedLength(songId, 0, Long.MAX_VALUE)
            Log.d(TAG, "Cached content length for $songId: $contentLength bytes")

            if (contentLength <= 0) {
                Log.w(TAG, "No cached data found for $songId")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mediaStoreMimeType = toMediaStoreMimeType(mimeType)
                exportViaMediaStore(context, songId, filename, mediaStoreMimeType, cache)
            } else {
                exportViaDirectFile(songId, filename, cache)
            }

            Log.i(TAG, "Successfully exported '$filename' to Music/$SUBFOLDER/")
            showToast(context, "Saved to Music/$SUBFOLDER/$filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export song $songId", e)
            showToast(context, "Export failed: ${e.message}")
        }
    }

    private fun exportViaMediaStore(
        context: Context,
        songId: String,
        filename: String,
        mimeType: String,
        cache: SimpleCache,
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
                readCacheToStream(cache, songId, outputStream)
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
        songId: String,
        filename: String,
        cache: SimpleCache,
    ) {
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            SUBFOLDER,
        )
        musicDir.mkdirs()

        val file = File(musicDir, filename)
        FileOutputStream(file).use { outputStream ->
            readCacheToStream(cache, songId, outputStream)
        }
    }

    private fun readCacheToStream(
        cache: SimpleCache,
        songId: String,
        outputStream: OutputStream,
    ) {
        val dataSource = CacheDataSource(cache, null)
        try {
            val dataSpec = DataSpec.Builder()
                .setUri(songId.toUri())
                .setKey(songId)
                .build()
            val totalBytes = dataSource.open(dataSpec)
            Log.d(TAG, "Reading $totalBytes bytes from cache for $songId")

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            Log.d(TAG, "Wrote $totalRead bytes to output")
        } finally {
            dataSource.close()
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

    private fun toMediaStoreMimeType(mimeType: String): String =
        when (mimeType) {
            "audio/webm" -> "audio/ogg"
            else -> mimeType
        }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(200)
    }
}
