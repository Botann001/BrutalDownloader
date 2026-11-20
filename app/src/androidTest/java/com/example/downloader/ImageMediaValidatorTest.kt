package com.example.downloader

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class ImageMediaValidatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    private val jpeg = DownloadFormat("jpg", "image/jpeg")
    private val png = DownloadFormat("png", "image/png")
    private val webp = DownloadFormat("webp", "image/webp")
    private val gif = DownloadFormat("gif", "image/gif")

    @After
    fun cleanUp() {
        val failures = mutableListOf<Throwable>()
        for (file in files.asReversed()) {
            try {
                assertTrue("Could not delete cache fixture: $file", !file.exists() || file.delete())
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }
        if (failures.isNotEmpty()) {
            val failure = AssertionError("Image validator test cleanup failed")
            failures.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Test
    fun validatesJpegAndReportsOriginalDimensions() {
        assertValid(createImage(Bitmap.CompressFormat.JPEG, jpeg, 13, 7), jpeg, 13, 7)
    }

    @Test
    fun validatesPngAndReportsOriginalDimensions() {
        assertValid(createImage(Bitmap.CompressFormat.PNG, png, 7, 13), png, 7, 13)
    }

    @Suppress("DEPRECATION")
    @Test
    fun validatesWebpAndReportsOriginalDimensions() {
        assertValid(createImage(Bitmap.CompressFormat.WEBP, webp, 11, 9), webp, 11, 9)
    }

    @Test
    fun validatesGifAndReportsOriginalDimensions() {
        // Bitmap.compress has no GIF encoder; use a complete one-pixel GIF fixture.
        val bytes = Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
            Base64.DEFAULT
        )
        val file = createFile(gif.extension)
        file.writeBytes(bytes)
        assertValid(file, gif, 1, 1)
    }

    @Test
    fun rejectsTruncatedPngWithoutPixelData() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        val idat = (8..bytes.size - 4).first { offset ->
            bytes[offset] == 'I'.code.toByte() && bytes[offset + 1] == 'D'.code.toByte() &&
                bytes[offset + 2] == 'A'.code.toByte() && bytes[offset + 3] == 'T'.code.toByte()
        }
        // Preserve the dimensions and IDAT header, but remove all compressed pixels.
        file.writeBytes(bytes.copyOf(idat + 4))
        expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
    }

    @Test
    fun validatesPngWithAncillaryChunkLargerThanReadBuffer() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        val afterHeader = 8 + 12 + 13
        val text = "Note\u0000" + "a".repeat(9000)
        file.writeBytes(
            bytes.copyOfRange(0, afterHeader) + pngChunk("tEXt", text.toByteArray(Charsets.US_ASCII)) +
                bytes.copyOfRange(afterHeader, bytes.size)
        )
        assertValid(file, png, 13, 7)
    }

    @Test
    fun rejectsPngWithCorruptedChunkCrc() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val original = file.readBytes()
        for (type in listOf("IHDR", "IDAT", "IEND")) {
            val bytes = original.copyOf()
            val offset = findPngChunk(bytes, type)
            val crcOffset = offset + 8 + ByteBuffer.wrap(bytes, offset, 4).int
            bytes[crcOffset] = (bytes[crcOffset].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            val failure = expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
            assertTrue("Expected CRC failure for $type", failure.message.orEmpty().contains("CRC"))
        }
    }

    @Test
    fun rejectsPngWithCorruptedAncillaryData() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        val iend = findPngChunk(bytes, "IEND")
        val chunk = pngChunk("tEXt", "Note\u0000test".toByteArray(Charsets.US_ASCII))
        chunk[chunk.size - 5] = (chunk[chunk.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes.copyOfRange(0, iend) + chunk + bytes.copyOfRange(iend, bytes.size))
        val failure = expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
        assertTrue("Expected ancillary CRC failure", failure.message.orEmpty().contains("CRC"))
    }

    @Test
    fun rejectsPngWithoutIend() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(findPngChunk(bytes, "IEND")))
        expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
    }

    @Test
    fun rejectsPngWithTruncatedChunkHeaderDataOrCrc() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        val idat = findPngChunk(bytes, "IDAT")
        val idatLength = ByteBuffer.wrap(bytes, idat, 4).int
        assertTrue("Expected compressed PNG pixels", idatLength > 1)
        val truncations = listOf(idat + 2, idat + 6, idat + 9, idat + 8 + idatLength + 3, bytes.size - 1)
        for (end in truncations) {
            file.writeBytes(bytes.copyOf(end))
            expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
        }
    }

    @Test
    fun rejectsPngWithOversizedOrUnavailableChunkData() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val original = file.readBytes()
        val idat = findPngChunk(original, "IDAT")
        // MAX_VALUE is legal as a length but exceeds this file; the others are unsigned values above the spec limit.
        for (length in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1)) {
            val bytes = original.copyOf()
            ByteBuffer.wrap(bytes, idat, 4).putInt(length)
            file.writeBytes(bytes)
            expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
        }
    }

    @Test
    fun rejectsPngWithoutNonemptyIdat() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val header = file.readBytes().copyOf(8 + 12 + 13)
        for (data in listOf(byteArrayOf(), pngChunk("IDAT", byteArrayOf()))) {
            file.writeBytes(header + data + pngChunk("IEND", byteArrayOf()))
            expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
        }
    }

    @Test
    fun rejectsPngWithNonemptyIend() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(findPngChunk(bytes, "IEND")) + pngChunk("IEND", byteArrayOf(0)))
        expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
    }

    @Test
    fun rejectsPngWithoutFirstThirteenByteIhdr() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 13, 7)
        val bytes = file.readBytes()
        val signature = bytes.copyOf(8)
        val headerData = bytes.copyOfRange(16, 29)
        val afterHeader = bytes.copyOfRange(33, bytes.size)
        val invalidHeaders = listOf(
            pngChunk("tEXt", "Note\u0000test".toByteArray(Charsets.US_ASCII)) + bytes.copyOfRange(8, 33),
            pngChunk("IHDR", headerData.copyOf(12)),
            pngChunk("IHDR", headerData.copyOf(14)),
            bytes.copyOfRange(8, 33) + pngChunk("IHDR", headerData)
        )
        for (header in invalidHeaders) {
            file.writeBytes(signature + header + afterHeader)
            expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
        }
    }

    @Test
    fun rejectsFakeImageSignatures() {
        val signatures = listOf(
            jpeg to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            png to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            webp to "RIFF\u0004\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII),
            gif to "GIF89a".toByteArray(Charsets.US_ASCII)
        )
        for ((format, bytes) in signatures) {
            val file = createFile(format.extension)
            file.writeBytes(bytes)
            expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), format) }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun rejectsContentMimeMismatch() {
        val encodings = listOf(
            jpeg to Bitmap.CompressFormat.JPEG,
            png to Bitmap.CompressFormat.PNG,
            webp to Bitmap.CompressFormat.WEBP
        )
        for ((actual, encoding) in encodings) {
            val file = createImage(encoding, actual, 5, 3)
            for (expected in listOf(jpeg, png, webp, gif).filter { it != actual }) {
                expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), expected) }
            }
        }
    }

    @Test
    fun rejectsEmptyFile() {
        val file = createFile(png.extension)
        expectIOException { ImageMediaValidator.validate(context, Uri.fromFile(file), png) }
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val file = createImage(Bitmap.CompressFormat.PNG, png, 5, 3)
        expectIOException {
            ImageMediaValidator.validate(context, Uri.fromFile(file), DownloadFormat("bmp", "image/bmp"))
        }
    }

    private fun findPngChunk(bytes: ByteArray, type: String): Int {
        var offset = 8
        while (offset <= bytes.size - 12) {
            if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == type) return offset
            offset += 12 + ByteBuffer.wrap(bytes, offset, 4).int
        }
        throw AssertionError("Missing $type in generated PNG fixture")
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            output.writeInt(crc.value.toInt())
        }
        return bytes.toByteArray()
    }

    private fun createFile(extension: String): File =
        File.createTempFile("image-validator-", ".$extension", context.cacheDir).also { files.add(it) }

    private fun createImage(
        encoding: Bitmap.CompressFormat,
        format: DownloadFormat,
        width: Int,
        height: Int
    ): File {
        val file = createFile(format.extension)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(35, 110, 190))
            file.outputStream().use { output ->
                assertTrue("Could not encode ${format.extension} fixture", bitmap.compress(encoding, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun assertValid(file: File, format: DownloadFormat, width: Int, height: Int) {
        assertEquals(
            "Dimensi foto: $width × $height",
            ImageMediaValidator.validate(context, Uri.fromFile(file), format)
        )
    }

    private fun expectIOException(action: () -> Unit): IOException {
        try {
            action()
        } catch (failure: IOException) {
            assertTrue("Expected actionable error", failure.message.orEmpty().isNotBlank())
            return failure
        }
        throw AssertionError("Expected IOException")
    }
}
