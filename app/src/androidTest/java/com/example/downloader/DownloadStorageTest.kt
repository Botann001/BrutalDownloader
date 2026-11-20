package com.example.downloader

import android.annotation.TargetApi
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val downloads = mutableListOf<PendingDownload>()
    private val mimeType = "application/octet-stream"

    @After
    fun cleanUp() {
        val failures = mutableListOf<Throwable>()
        for (download in downloads.asReversed()) {
            try {
                download.close()
            } catch (failure: Throwable) {
                failures.add(failure)
            }
            // Published rows also need deletion; never stop at an earlier cleanup failure.
            try {
                resolver.delete(download.uri, null, null)
                assertRowMissing(download.uri)
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }
        if (failures.isNotEmpty()) {
            val failure = AssertionError("Download test cleanup failed")
            failures.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun closeWithoutPublishDeletesRow() {
        val pending = createDownload()
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        pending.outputStream.use { it.write(bytes) }
        assertMetadata(pending, 1)
        assertArrayEquals(bytes, readBytes(pending.uri))

        pending.close()
        pending.close()
        assertRowMissing(pending.uri)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun publishPreservesBytesAndClearsPendingFlag() {
        val pending = createDownload()
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        pending.outputStream.use {
            it.write(bytes)
            it.flush()
        }
        // Metadata and bytes must be readable before publication, with the stream closed.
        assertMetadata(pending, 1)
        assertArrayEquals(bytes, readBytes(pending.uri))
        val location = pending.publish()
        assertEquals(Environment.DIRECTORY_DOWNLOADS + "/BrutalDownloader/" + pending.displayName, location)
        assertEquals(location, pending.publish())
        assertMetadata(pending, 0)

        pending.close()
        pending.close()
        assertMetadata(pending, 0)
        assertArrayEquals(bytes, readBytes(pending.uri))
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun duplicateRequestedNamesPreserveOriginalDownload() {
        val name = "storage-test-" + UUID.randomUUID() + ".bin"
        val original = createDownload(name)
        val originalBytes = "original bytes".toByteArray(Charsets.UTF_8)
        original.outputStream.use { it.write(originalBytes) }
        original.publish()
        original.close()

        val duplicate = createDownload(name)
        val duplicateBytes = "different bytes".toByteArray(Charsets.UTF_8)
        assertNotEquals(original.displayName, duplicate.displayName)
        assertNotEquals(original.uri, duplicate.uri)
        duplicate.outputStream.use { it.write(duplicateBytes) }
        assertMetadata(duplicate, 1)
        assertArrayEquals(originalBytes, readBytes(original.uri))
        duplicate.publish()
        duplicate.close()

        assertMetadata(original, 0)
        assertMetadata(duplicate, 0)
        assertArrayEquals(originalBytes, readBytes(original.uri))
        assertArrayEquals(duplicateBytes, readBytes(duplicate.uri))
    }

    @Test
    fun commitFailureRollsBackOnUseClose() {
        val failure = IOException("commit failed")
        var commits = 0
        var rollbacks = 0
        val pending = PendingDownload(Uri.EMPTY, "fake.bin", ByteArrayOutputStream(), {
            commits++
            throw failure
        }, { rollbacks++ })

        assertSame(failure, expectIOException { pending.use { it.publish() } })
        pending.close()
        assertEquals(1, commits)
        assertEquals(1, rollbacks)
    }

    @Test
    fun cleanupFailureDoesNotMaskWriteFailure() {
        val writeFailure = IOException("write failed")
        val cleanupFailure = IOException("rollback failed")
        var streamCloses = 0
        var rollbacks = 0
        val stream = object : OutputStream() {
            override fun write(value: Int) { throw writeFailure }
            override fun close() { streamCloses++ }
        }
        val pending = PendingDownload(Uri.EMPTY, "fake.bin", stream, {
            throw AssertionError("Must not commit failed write")
        }, {
            rollbacks++
            throw cleanupFailure
        })

        val thrown = expectIOException { pending.use { it.outputStream.write(1) } }
        pending.close()
        assertSame(writeFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(cleanupFailure, thrown.suppressed.single())
        assertEquals(1, streamCloses)
        assertEquals(1, rollbacks)
    }

    @Test
    fun publishAndCloseAreIdempotentWithoutRollbackAfterCommit() {
        var commits = 0
        var rollbacks = 0
        var streamCloses = 0
        val stream = object : ByteArrayOutputStream() {
            override fun close() {
                streamCloses++
                super.close()
            }
        }
        val pending = PendingDownload(Uri.EMPTY, "fake.bin", stream, {
            commits++
            "Downloads/fake.bin"
        }, { rollbacks++ })
        pending.use {
            it.outputStream.write(42)
            assertEquals("Downloads/fake.bin", it.publish())
            assertEquals("Downloads/fake.bin", it.publish())
            it.close()
            it.close()
        }
        assertEquals(1, commits)
        assertEquals(1, streamCloses)
        assertEquals(0, rollbacks)
        assertArrayEquals(byteArrayOf(42), stream.toByteArray())
    }

    @Test
    fun streamCloseFailurePreventsPublish() {
        // Cover both publish closing the stream and the caller closing it first.
        for (closeExplicitly in listOf(false, true)) {
            val closeFailure = IOException("close failed")
            var commits = 0
            var rollbacks = 0
            var streamCloses = 0
            val stream = object : ByteArrayOutputStream() {
                override fun close() {
                    streamCloses++
                    throw closeFailure
                }
            }
            val pending = PendingDownload(Uri.EMPTY, "fake.bin", stream, {
                commits++
                "Downloads/fake.bin"
            }, { rollbacks++ })
            pending.use {
                if (closeExplicitly) {
                    assertSame(closeFailure, expectIOException { it.outputStream.close() })
                }
                val failure = expectIOException { it.publish() }
                if (closeExplicitly) assertSame(closeFailure, failure.cause)
                else assertSame(closeFailure, failure)
            }
            pending.close()
            assertEquals(0, commits)
            assertEquals(1, streamCloses)
            assertEquals(1, rollbacks)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun createDownload(
        name: String = "storage-test-" + UUID.randomUUID() + ".bin"
    ): PendingDownload = DownloadStorage(context).create(name, mimeType).also { downloads.add(it) }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun assertMetadata(pending: PendingDownload, expectedPending: Int) {
        val projection = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.IS_PENDING
        )
        val cursor = resolver.query(pending.uri, projection, null, null, null)
            ?: throw AssertionError("No cursor for " + pending.uri)
        cursor.use {
            assertTrue("Missing download row: " + pending.uri, it.moveToFirst())
            assertEquals(pending.displayName, it.getString(0))
            assertEquals(mimeType, it.getString(1))
            assertEquals(Environment.DIRECTORY_DOWNLOADS + "/BrutalDownloader/", it.getString(2))
            assertEquals(expectedPending, it.getInt(3))
            assertFalse("Expected one download row", it.moveToNext())
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri)
            ?: throw AssertionError("No input stream for " + uri)
        return input.use { it.readBytes() }
    }

    private fun assertRowMissing(uri: Uri) {
        val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?: throw AssertionError("No cursor while checking deletion: " + uri)
        cursor.use { assertFalse("Download row still exists: " + uri, it.moveToFirst()) }
    }

    private fun expectIOException(action: () -> Unit): IOException {
        try {
            action()
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("Expected IOException")
    }
}
