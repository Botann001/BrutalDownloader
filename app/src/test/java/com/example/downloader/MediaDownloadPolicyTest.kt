package com.example.downloader

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadPolicyTest {
    @Test
    fun isoBmffUsesFtypAtOffsetFourAndRequestedMediaMode() {
        val header = bytes(0, 0, 0, 24) + ascii("ftypisom")

        assertEquals(DownloadFormat("mp4", "video/mp4"), detect(header, audioOnly = false))
        assertEquals(DownloadFormat("m4a", "audio/mp4"), detect(header, audioOnly = true))
        expectIo { detect(ascii("ftypisom")) }
        expectIo { detect(bytes(0, 0, 0) + ascii("ftypisom")) }
    }

    @Test
    fun detectsId3AndMpegAudioVersionsWithoutConfusingAdts() {
        val mp3Headers = listOf(
            ascii("ID3"),
            bytes(0xff, 0xfb, 0x90, 0x64), // MPEG-1 Layer III.
            bytes(0xff, 0xf3, 0x80, 0x00), // MPEG-2 Layer III.
            bytes(0xff, 0xe3, 0x80, 0x00) // MPEG-2.5 Layer III.
        )
        for (header in mp3Headers) {
            for (audioOnly in listOf(false, true)) {
                assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(header, audioOnly))
            }
        }
        for (header in listOf(adts(), adts(secondByte = 0xf9))) {
            for (audioOnly in listOf(false, true)) {
                assertEquals(DownloadFormat("aac", "audio/aac"), detect(header, audioOnly))
            }
        }
    }

    @Test
    fun id3MetadataDoesNotOverrideAvailableAacOrMp3Payload() {
        val mp3 = bytes(0xff, 0xfb, 0x90, 0x64)
        // Embedded magic in metadata must not be mistaken for the actual payload.
        val metadata = ascii("ID3 ftyp OggS") + ByteArray(130)
        for (version in 2..4) {
            val tag = id3Header(metadata.size, version) + metadata
            for (audioOnly in listOf(false, true)) {
                assertEquals(DownloadFormat("aac", "audio/aac"), detect(tag + adts(), audioOnly))
                assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(tag + mp3, audioOnly))
                assertEquals(DownloadFormat("flac", "audio/flac"), detect(tag + ascii("fLaC"), audioOnly))
            }
        }
    }

    @Test
    fun skipsOptionalId3v24FooterBeforeSniffingPayload() {
        val header = id3Header(4, version = 4, flags = 0x10)
        val footer = ascii("3DI") + header.copyOfRange(3, 10)
        val tag = header + ascii("test") + footer
        assertEquals(DownloadFormat("aac", "audio/aac"), detect(tag + adts()))
        assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(tag))
        assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(tag.copyOf(tag.size - 1)))
        expectIo { detect(header + ascii("test") + ByteArray(10) + adts()) }
        expectIo { detect(tag.also { it[17] = 3 } + adts()) } // Footer version mismatch.
    }

    @Test
    fun unavailableId3PayloadRemainsProvisionalAndRequiresMpegTrackGuard() {
        // These are not proof of MPEG audio. The caller MUST reconcile audio/mpeg
        // with MediaExtractor before publishing, including when unseen payload is AAC.
        val prefixes = listOf(
            ascii("ID3"),
            id3Header(0),
            id3Header(1024) + ascii("metadata prefix"),
            id3Header(0x0fffffff) + adts(),
            id3Header(0) + bytes(0xff, 0xfb),
            id3Header(0) + adts().copyOf(6)
        )
        for (prefix in prefixes) {
            assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(prefix))
        }
    }

    @Test
    fun skipsManyId3TagsIterativelyWithStrictlyAdvancingOffsets() {
        val tag = id3Header(0)
        val tags = ByteArray(tag.size * 20_000)
        for (offset in tags.indices step tag.size) {
            tag.copyInto(tags, offset)
        }
        assertEquals(DownloadFormat("aac", "audio/aac"), detect(tags + adts()))
        assertEquals(DownloadFormat("mp3", "audio/mpeg"), detect(tags))
        expectIo { detect(tags + ascii("<html>not media</html>")) }
    }

    @Test
    fun rejectsMalformedId3HeadersAndInvalidPayloads() {
        val malformed = listOf(
            id3Header(0, version = 1),
            id3Header(0, version = 5),
            id3Header(0).also { it[4] = 0xff.toByte() },
            id3Header(0, version = 2, flags = 0x10),
            id3Header(0, version = 3, flags = 0x10),
            id3Header(0, flags = 1),
            id3Header(0).copyOf(9)
        ) + (6..9).map { index -> id3Header(0).also { it[index] = 0x80.toByte() } }
        malformed.forEach { header -> expectIo { detect(header + adts()) } }
        expectIo { detect(id3Header(0) + ascii("{\"error\":\"expired\"}")) }
    }

    @Test
    fun rejectsMpegLayersOneAndTwoEvenAfterId3() {
        for (secondByte in listOf(0xff, 0xfd, 0xf7, 0xf5, 0xe7, 0xe5)) {
            val frame = bytes(0xff, secondByte, 0x90, 0)
            expectIo { detect(frame) }
            expectIo { detect(id3Header(0) + frame) }
        }
    }

    @Test
    fun rejectsReservedAndIncompleteMpegAudioHeaders() {
        val invalid = listOf(
            bytes(0xff, 0xfb, 0x90),
            bytes(0xfe, 0xfb, 0x90, 0), // No frame sync.
            bytes(0xff, 0xeb, 0x90, 0), // Reserved MPEG version.
            bytes(0xff, 0xf9, 0x90, 0), // Reserved layer, not a complete ADTS header.
            bytes(0xff, 0xfb, 0xf0, 0), // Invalid bitrate index.
            bytes(0xff, 0xfb, 0x9c, 0), // Reserved sample rate.
            bytes(0xff, 0xfb, 0x90, 2) // Reserved emphasis.
        )
        invalid.forEach { header -> expectIo { detect(header) } }
    }

    @Test
    fun validatesAdtsHeaderIncludingCrcAndFrameSize() {
        assertEquals(
            DownloadFormat("aac", "audio/aac"),
            detect(adts(secondByte = 0xf0) + bytes(0, 0))
        )
        val invalid = listOf(
            adts().copyOf(6),
            adts(secondByte = 0xf0), // CRC bytes missing.
            adts(secondByte = 0xe1), // Missing ADTS sync bits.
            adts().also { it[2] = 0x74.toByte() }, // Reserved sample rate.
            adts().also { it[4] = 0; it[5] = 0x1f } // Frame shorter than its header.
        )
        invalid.forEach { header -> expectIo { detect(header) } }
    }

    @Test
    fun webmRequiresEbmlDocTypeAndUsesRequestedMediaMode() {
        val header = ebml(ascii("webm"))

        assertEquals(DownloadFormat("webm", "video/webm"), detect(header, audioOnly = false))
        assertEquals(DownloadFormat("webm", "audio/webm"), detect(header, audioOnly = true))
        // EBML sizes are variable-length integers, not always single-byte values.
        val twoByteSize = header.copyOfRange(0, 4) + bytes(0x40, header.size - 5) +
            header.copyOfRange(5, header.size)
        assertEquals(DownloadFormat("webm", "audio/webm"), detect(twoByteSize))
    }

    @Test
    fun rejectsMatroskaAndMisleadingOrMalformedEbml() {
        val webm = ebml(ascii("webm"))
        val invalid = listOf(
            ebml(ascii("matroska")),
            ebml(ascii("matroska")) + ascii("webm"),
            ebml(ascii("webm-extra")),
            ebml(ascii("WEBM")),
            webm.copyOf(webm.size - 1),
            bytes(0x1a, 0x45, 0xdf, 0xa3),
            bytes(0x1a, 0x45, 0xdf, 0xa3, 0x80) + ascii("webm"),
            bytes(0x1a, 0x45, 0xdf, 0xa3, 0xff) + ascii("webm"),
            webm.also { it[5] = 0 }, // Invalid child ID.
            ebml(ascii("webm")).also { it[11] = 0 }, // Invalid DocType size.
            ebml(ascii("webm")).also { it[11] = 0xff.toByte() }, // Unknown DocType size.
            ebml(ascii("webm")).also { it[11] = 0xfe.toByte() } // Child exceeds header.
        )
        invalid.forEach { header -> expectIo { detect(header) } }
        // The string alone in a different EBML element is not a DocType.
        val otherElement = ebml(ascii("webm")).also { it[10] = 0x83.toByte() }
        expectIo { detect(otherElement) }
    }

    @Test
    fun audioContainersAreAllowedForBothButtons() {
        val cases = listOf(
            ascii("OggS") to DownloadFormat("ogg", "audio/ogg"),
            ascii("fLaC") to DownloadFormat("flac", "audio/flac"),
            (ascii("RIFF") + bytes(36, 0, 0, 0) + ascii("WAVE")) to
                DownloadFormat("wav", "audio/wav")
        )
        for ((header, expected) in cases) {
            assertEquals(expected, detect(header, audioOnly = true))
            assertEquals(expected, detect(header, audioOnly = false))
        }
        expectIo { detect(ascii("RIFF") + bytes(36, 0, 0, 0) + ascii("AVI ")) }
        expectIo { detect(ascii("RIFFWAVE")) }
    }

    @Test
    fun rejectsEmptyHtmlJsonAndUnknownBodiesWithActionableMessage() {
        val invalid = listOf(
            byteArrayOf(),
            ascii("<!DOCTYPE html><html>Login required</html>"),
            ascii("  <html><body>Access denied</body></html>"),
            ascii("{\"error\":\"download expired\"}"),
            ascii("[\"https://example.com/video.mp4\"]"),
            ascii("https://example.com/audio.mp3"),
            ascii("Content-Type: video/mp4\r\n\r\nnot media"),
            ascii("<html>ftyp ID3 OggS fLaC webm</html>"),
            bytes(0, 1, 2, 3, 4, 5, 6, 7),
            bytes(0xff, 0xff, 0xff, 0xff)
        )
        for (header in invalid) {
            for (audioOnly in listOf(false, true)) {
                val message = expectIo { detect(header, audioOnly) }.message.orEmpty()
                assertTrue(message.contains("tautan media langsung"))
                assertTrue(message.contains("coba lagi"))
            }
        }
    }

    @Test
    fun rejectsEveryTruncatedSignatureWithoutIndexErrors() {
        val signatures = listOf(
            bytes(0, 0, 0, 24) + ascii("ftyp"),
            ascii("ID3"),
            bytes(0xff, 0xfb, 0x90, 0x64),
            adts(),
            ebml(ascii("webm")),
            ascii("OggS"),
            ascii("fLaC"),
            ascii("RIFF") + bytes(36, 0, 0, 0) + ascii("WAVE")
        )
        for (header in signatures) {
            for (length in header.indices) {
                expectIo { detect(header.copyOf(length)) }
            }
        }
    }

    @Test
    fun detectsImageMagicAndRejectsEveryImageInAudioOnlyMode() {
        for ((header, expected) in imageSignatures()) {
            assertEquals(expected, detect(header, audioOnly = false))
            assertEquals(expected, detect(header + ascii("extra payload"), audioOnly = false))
            val message = expectIo { detect(header, audioOnly = true) }.message.orEmpty()
            assertTrue(message.startsWith("Konten berupa foto, bukan audio"))
            assertTrue(message.contains("tautan media yang berisi audio"))
            assertTrue(message.contains("coba lagi"))
        }
    }

    @Test
    fun availableImagesAfterId3CannotFallBackToProvisionalAudio() {
        for ((header, expected) in imageSignatures()) {
            val tagged = id3Header(0) + header
            assertEquals(expected, detect(tagged, audioOnly = false))
            val message = expectIo { detect(tagged, audioOnly = true) }.message.orEmpty()
            assertTrue(message.startsWith("Konten berupa foto, bukan audio"))
        }
    }

    @Test
    fun rejectsEveryTruncatedImageSignatureInBothModes() {
        for ((header, _) in imageSignatures()) {
            for (length in header.indices) {
                for (audioOnly in listOf(false, true)) {
                    val message = expectIo { detect(header.copyOf(length), audioOnly) }.message.orEmpty()
                    assertTrue(message.contains("tidak dikenali"))
                }
            }
        }
    }

    @Test
    fun rejectsForgedImageSignaturesInBothModes() {
        for ((header, expected) in imageSignatures()) {
            // RIFF's four-byte size field is not part of the WebP signature.
            val signatureIndices = header.indices.filterNot {
                expected.extension == "webp" && it in 4..7
            }
            for (index in signatureIndices) {
                val forged = header.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
                for (audioOnly in listOf(false, true)) {
                    val message = expectIo { detect(forged, audioOnly) }.message.orEmpty()
                    assertTrue(message.contains("tidak dikenali"))
                }
            }
        }
    }

    @Test
    fun riffFormTypeDistinguishesWavFromWebpAtOffsetEight() {
        for (size in listOf(bytes(0, 0, 0, 0), bytes(36, 0, 0, 0), bytes(255, 255, 255, 255))) {
            val riff = ascii("RIFF") + size
            for (audioOnly in listOf(false, true)) {
                assertEquals(DownloadFormat("wav", "audio/wav"), detect(riff + ascii("WAVEWEBP"), audioOnly))
                expectIo { detect(riff + ascii("AVI WEBP WAVE"), audioOnly) }
                expectIo { detect(ascii("RIFFWEBP"), audioOnly) }
            }
            assertEquals(
                DownloadFormat("webp", "image/webp"),
                detect(riff + ascii("WEBPWAVE"), audioOnly = false)
            )
            val message = expectIo { detect(riff + ascii("WEBPWAVE")) }.message.orEmpty()
            assertTrue(message.startsWith("Konten berupa foto, bukan audio"))
        }
    }

    @Test
    fun doesNotGuessImagesFromMimeExtensionsOrEmbeddedMagic() {
        val misleading = listOf(
            ascii("https://example.com/photo.jpg"),
            ascii("photo.png photo.webp photo.gif"),
            ascii("Content-Type: image/jpeg\r\n\r\nnot an image"),
            ascii("image/png image/webp image/gif"),
            ascii("{\"url\":\"photo.png\",\"mime\":\"image/png\"}"),
            ascii("<html><img src=\"photo.jpg\">GIF89a RIFF WEBP</html>")
        ) + imageSignatures().flatMap { (header, _) ->
            listOf(ascii("<html>") + header + ascii("</html>"), bytes(0) + header)
        }
        for (header in misleading) {
            for (audioOnly in listOf(false, true)) {
                val message = expectIo { detect(header, audioOnly) }.message.orEmpty()
                assertTrue(message.contains("foto/audio/video"))
                assertTrue(message.contains("tautan media langsung"))
                assertTrue(message.contains("coba lagi"))
            }
        }
    }

    @Test
    fun bestQualityIsMaximumActualHeightRegardlessOfOrder() {
        assertEquals(1080, MediaDownloadPolicy.selectVideoHeight(listOf(360, 1080, 720, 360), null))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(listOf(360), null))
    }

    @Test
    fun requestedQualityUsesHighestAtOrBelowTargetThenMinimumFallback() {
        val heights = listOf(1080, 360, 720)
        assertEquals(1080, MediaDownloadPolicy.selectVideoHeight(heights, 1080))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(heights, 360))
        assertEquals(720, MediaDownloadPolicy.selectVideoHeight(heights, 900))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(heights, 240))
        assertEquals(1080, MediaDownloadPolicy.selectVideoHeight(heights, 2160))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(listOf(360), 1080))
        assertEquals(1080, MediaDownloadPolicy.selectVideoHeight(listOf(1080), 360))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(heights, 0))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(heights, -1))
    }

    @Test
    fun ignoresNonpositiveCandidatesAndFailsWhenNoValidHeightExists() {
        assertEquals(720, MediaDownloadPolicy.selectVideoHeight(listOf(0, -1, 720, 360), null))
        assertEquals(360, MediaDownloadPolicy.selectVideoHeight(listOf(0, 720, -1, 360), 240))
        for (heights in listOf(emptyList(), listOf(0), listOf(-1080, 0, -360))) {
            for (target in listOf(null, 360)) {
                try {
                    MediaDownloadPolicy.selectVideoHeight(heights, target)
                    throw AssertionError("Expected failure for heights=$heights, target=$target")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message.orEmpty().contains("resolusi"))
                }
            }
        }
    }

    @Test
    fun acceptsExactKnownLengthsAndNonemptyUnknownLengths() {
        MediaDownloadPolicy.requireComplete(1, 1)
        MediaDownloadPolicy.requireComplete(4096, 4096)
        MediaDownloadPolicy.requireComplete(Long.MAX_VALUE, Long.MAX_VALUE)
        MediaDownloadPolicy.requireComplete(-1, 1)
        MediaDownloadPolicy.requireComplete(-1, 4096)
    }

    @Test
    fun rejectsEmptyNegativeTruncatedAndOversizedBodies() {
        for (expected in listOf(-1L, 0L, 100L)) {
            expectIo { MediaDownloadPolicy.requireComplete(expected, 0) }
            expectIo { MediaDownloadPolicy.requireComplete(expected, -1) }
        }
        for ((expected, actual) in listOf(100L to 99L, 100L to 101L, 0L to 1L)) {
            val message = expectIo { MediaDownloadPolicy.requireComplete(expected, actual) }.message.orEmpty()
            assertTrue(message.contains("$expected byte"))
            assertTrue(message.contains("$actual byte"))
            assertTrue(message.contains("unduh ulang"))
        }
        expectIo { MediaDownloadPolicy.requireComplete(Long.MAX_VALUE, 1) }
        expectIo { MediaDownloadPolicy.requireComplete(1, Long.MAX_VALUE) }
    }

    private fun imageSignatures(): List<Pair<ByteArray, DownloadFormat>> = listOf(
        bytes(0xff, 0xd8, 0xff) to DownloadFormat("jpg", "image/jpeg"),
        bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) to DownloadFormat("png", "image/png"),
        (ascii("RIFF") + bytes(36, 0, 0, 0) + ascii("WEBP")) to DownloadFormat("webp", "image/webp"),
        ascii("GIF87a") to DownloadFormat("gif", "image/gif"),
        ascii("GIF89a") to DownloadFormat("gif", "image/gif")
    )

    private fun detect(header: ByteArray, audioOnly: Boolean = true): DownloadFormat =
        MediaDownloadPolicy.detectFormat(header, audioOnly)

    private fun id3Header(size: Int, version: Int = 4, flags: Int = 0): ByteArray =
        ascii("ID3") + bytes(
            version, 0, flags,
            (size shr 21) and 0x7f,
            (size shr 14) and 0x7f,
            (size shr 7) and 0x7f,
            size and 0x7f
        )

    private fun adts(secondByte: Int = 0xf1): ByteArray =
        bytes(0xff, secondByte, 0x50, 0x80, 0x01, 0x7f, 0xfc)

    private fun ebml(docType: ByteArray): ByteArray {
        val version = bytes(0x42, 0x86, 0x81, 1)
        val body = version + bytes(0x42, 0x82, 0x80 or docType.size) + docType
        return bytes(0x1a, 0x45, 0xdf, 0xa3, 0x80 or body.size) + body
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun expectIo(action: () -> Unit): IOException {
        try {
            action()
        } catch (expected: IOException) {
            return expected
        }
        throw AssertionError("Expected IOException")
    }
}
