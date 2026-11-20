package com.example.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Transactional downloads. Legacy storage permission must be granted by the caller. */
class DownloadStorage(context: Context) {
    private val context = context.applicationContext ?: context

    fun create(displayName: String, mimeType: String): PendingDownload {
        require(mimeType.isNotBlank()) { "A MIME type is required" }
        val name = uniqueName(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStore(name, mimeType)
        } else {
            createLegacy(name, mimeType)
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun createMediaStore(name: String, mimeType: String): PendingDownload {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/BrutalDownloader/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create a download in MediaStore")
        val rollback: () -> Unit = {
            if (resolver.delete(uri, null, null) != 1) {
                throw IOException("Unable to remove pending download: $uri")
            }
        }
        return try {
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to open pending download: $uri")
            PendingDownload(uri, name, stream, {
                val published = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                if (resolver.update(uri, published, null, null) != 1) {
                    throw IOException("Unable to publish download: $uri")
                }
                "$relativePath$name"
            }, rollback)
        } catch (failure: Throwable) {
            preservingFailure(failure, rollback)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(name: String, mimeType: String): PendingDownload {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BrutalDownloader"
        )
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create download directory: $directory")
        }
        val temporary = File.createTempFile(".download-", ".pending", directory)
        val destination = File(directory, name)
        var ownsDestination = false
        val rollback: () -> Unit = {
            if (ownsDestination) deleteFiles(temporary, destination) else deleteFiles(temporary)
        }
        return try {
            val stream = FileOutputStream(temporary)
            PendingDownload(Uri.fromFile(temporary), name, stream, {
                // O_EXCL is essential: even an unexpected UUID collision must not overwrite.
                val descriptor = Os.open(
                    destination.absolutePath,
                    OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL,
                    438 // 0666, subject to the process umask.
                )
                ownsDestination = true
                try {
                    try {
                        FileOutputStream(descriptor).use { output ->
                            temporary.inputStream().use { input -> input.copyTo(output) }
                            output.flush()
                            descriptor.sync()
                        }
                    } finally {
                        // Android streams constructed from a descriptor may not own it.
                        if (descriptor.valid()) Os.close(descriptor)
                    }
                    deleteFiles(temporary)
                } catch (failure: Throwable) {
                    preservingFailure(failure) {
                        deleteFiles(destination)
                        ownsDestination = false
                    }
                    throw failure
                }
                // Indexing is best effort and must not undo a successfully committed file.
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destination.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                } catch (_: RuntimeException) {
                    // The complete file remains accessible even if the scanner is unavailable.
                }
                destination.absolutePath
            }, rollback)
        } catch (failure: Throwable) {
            preservingFailure(failure, rollback)
            throw failure
        }
    }

    private fun uniqueName(requested: String): String {
        val sanitized = requested.map { character ->
            if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
        }.joinToString("").trim().trim('.').ifBlank { "download" }
        val dot = sanitized.lastIndexOf('.')
        val extension = if (dot > 0 && sanitized.substring(dot + 1).matches(Regex("[A-Za-z0-9]{1,16}"))) {
            sanitized.substring(dot)
        } else {
            ""
        }
        val stem = if (extension.isEmpty()) sanitized else sanitized.substring(0, dot)
        // Bound UTF-8 bytes, not UTF-16 characters, without cutting a surrogate pair.
        val boundedStem = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < stem.length) {
            val codePoint = stem.codePointAt(index)
            val part = String(Character.toChars(codePoint))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 140) break
            boundedStem.append(part)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return "$boundedStem-${UUID.randomUUID()}$extension"
    }
}

/**
 * Close the stream before inspecting [uri], then call [publish] to commit.
 * On legacy Android, [uri] identifies the staging file and is invalid after publication;
 * use the returned location afterward. Unpublished downloads are removed by [close].
 * Stream writes and metadata inspection must not run concurrently with publish/close.
 */
class PendingDownload internal constructor(
    val uri: Uri,
    val displayName: String,
    stream: OutputStream,
    private val commit: () -> String,
    private val rollback: () -> Unit
) : Closeable {
    private val trackedStream = DownloadOutputStream(stream)
    val outputStream: OutputStream get() = trackedStream
    private var closed = false
    private var publishedLocation: String? = null

    @Synchronized
    fun publish(): String {
        publishedLocation?.let { return it }
        check(!closed) { "The pending download is closed" }
        trackedStream.close()
        trackedStream.closeFailure?.let { throw IOException("Download stream did not close successfully", it) }
        return commit().also { publishedLocation = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            trackedStream.close()
        } catch (error: Throwable) {
            failure = error
        }
        if (publishedLocation == null) {
            try {
                rollback()
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

private class DownloadOutputStream(private val delegate: OutputStream) : OutputStream() {
    private var closed = false
    var closeFailure: Throwable? = null
        private set

    override fun write(value: Int) {
        ensureOpen()
        delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        delegate.write(buffer, offset, length)
    }

    override fun flush() {
        ensureOpen()
        delegate.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } catch (failure: Throwable) {
            closeFailure = failure
            throw failure
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Download stream is closed")
    }
}

private fun deleteFiles(vararg files: File) {
    var failure: IOException? = null
    for (file in files) {
        try {
            if (!file.delete() && file.exists()) throw IOException("Unable to delete download: $file")
        } catch (error: Exception) {
            val deletionFailure = if (error is IOException) error else IOException("Unable to delete download: $file", error)
            if (failure == null) failure = deletionFailure else failure.addSuppressed(deletionFailure)
        }
    }
    failure?.let { throw it }
}

private inline fun preservingFailure(failure: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    }
}
