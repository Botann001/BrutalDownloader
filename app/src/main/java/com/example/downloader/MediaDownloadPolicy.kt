package com.example.downloader

import java.io.IOException

internal data class DownloadFormat(val extension: String, val mimeType: String)

internal object MediaDownloadPolicy {
    /**
     * Sniffs the container, not its tracks. Before publishing, the caller must use
     * MediaExtractor to verify audio/video tracks and reject video when audioOnly is true.
     * Image signatures require BitmapFactory validation before publishing as a photo.
     * ID3 without enough payload is only provisionally MP3: the caller MUST reconcile
     * audio/mpeg with the extracted track MIME before publishing, not merely find audio.
     */
    fun detectFormat(header: ByteArray, audioOnly: Boolean): DownloadFormat {
        val offset = skipId3Tags(header) ?: return DownloadFormat("mp3", "audio/mpeg")
        // Copy at most once, not once per tag, to keep long tag chains linear.
        val payload = if (offset == 0) header else header.copyOfRange(offset, header.size)
        val image = detectImageFormat(payload)
        if (image != null) {
            if (audioOnly) {
                throw IOException(
                    "Konten berupa foto, bukan audio. " +
                        "Pilih unduhan foto atau gunakan tautan media yang berisi audio, lalu coba lagi."
                )
            }
            return image
        }
        if (offset > 0 && (payload.size < 4 || hasIncompleteAdtsHeader(payload))) {
            return DownloadFormat("mp3", "audio/mpeg")
        }
        return when {
            payload.matches(4, "ftyp") -> if (audioOnly) {
                DownloadFormat("m4a", "audio/mp4")
            } else {
                DownloadFormat("mp4", "video/mp4")
            }
            isMpegAudio(payload) -> DownloadFormat("mp3", "audio/mpeg")
            isAdts(payload) -> DownloadFormat("aac", "audio/aac")
            isWebM(payload) -> DownloadFormat("webm", if (audioOnly) "audio/webm" else "video/webm")
            payload.matches(0, "OggS") -> DownloadFormat("ogg", "audio/ogg")
            payload.matches(0, "fLaC") -> DownloadFormat("flac", "audio/flac")
            payload.matches(0, "RIFF") && payload.matches(8, "WAVE") -> DownloadFormat("wav", "audio/wav")
            else -> throw IOException(
                "Format unduhan tidak dikenali atau bukan foto/audio/video. " +
                    "Gunakan tautan media langsung, bukan halaman HTML atau respons JSON, lalu coba lagi."
            )
        }
    }

    private fun detectImageFormat(header: ByteArray): DownloadFormat? = when {
        header.matches(0, "\u00ff\u00d8\u00ff") -> DownloadFormat("jpg", "image/jpeg")
        header.matches(0, "\u0089PNG\r\n\u001a\n") -> DownloadFormat("png", "image/png")
        header.matches(0, "RIFF") && header.matches(8, "WEBP") -> DownloadFormat("webp", "image/webp")
        header.matches(0, "GIF87a") || header.matches(0, "GIF89a") -> DownloadFormat("gif", "image/gif")
        else -> null
    }

    /** Null means that metadata extends beyond the prefix or only bare ID3 is available. */
    private fun skipId3Tags(header: ByteArray): Int? {
        var offset = 0
        while (header.matches(offset, "ID3")) {
            val remaining = header.size - offset
            if (remaining == 3) return null
            if (remaining < 10) invalidId3()
            val version = header.unsigned(offset + 3)
            val reservedFlags = when (version) {
                2 -> 0x3f
                3 -> 0x1f
                4 -> 0x0f
                else -> invalidId3()
            }
            val flags = header.unsigned(offset + 5)
            if (header.unsigned(offset + 4) == 0xff || flags and reservedFlags != 0) invalidId3()
            var size = 0
            for (index in 6..9) {
                val part = header.unsigned(offset + index)
                if (part and 0x80 != 0) invalidId3()
                size = (size shl 7) or part
            }
            val hasFooter = version == 4 && flags and 0x10 != 0
            val tagBytes = 10 + size + if (hasFooter) 10 else 0
            // Compare before adding offset, avoiding overflow for very long tag chains.
            if (tagBytes > remaining) return null
            val end = offset + tagBytes
            if (hasFooter) {
                val footer = end - 10
                if (!header.matches(footer, "3DI") ||
                    (3..9).any { header[footer + it] != header[offset + it] }
                ) invalidId3()
            }
            offset = end // Every iteration consumes at least the ten-byte header.
        }
        return offset
    }

    private fun invalidId3(): Nothing = throw IOException(
        "Metadata ID3 tidak valid. Gunakan tautan media langsung lain, lalu coba lagi."
    )

    private fun hasIncompleteAdtsHeader(header: ByteArray): Boolean {
        if (header.size < 2 || header.unsigned(0) != 0xff || header.unsigned(1) and 0xf6 != 0xf0) {
            return false
        }
        val required = if (header.unsigned(1) and 1 == 0) 9 else 7
        return header.size < required
    }

    fun selectVideoHeight(heights: List<Int>, targetHeight: Int?): Int {
        val available = heights.filter { it > 0 }
        require(available.isNotEmpty()) { "Tidak ada resolusi video yang valid. Pilih media lain." }
        return if (targetHeight == null) {
            available.maxOrNull()!!
        } else {
            available.filter { it <= targetHeight }.maxOrNull() ?: available.minOrNull()!!
        }
    }

    fun requireComplete(expectedBytes: Long, actualBytes: Long) {
        if (actualBytes <= 0) {
            throw IOException("Unduhan kosong atau ukuran tidak valid. Periksa koneksi lalu coba lagi.")
        }
        if (expectedBytes >= 0 && expectedBytes != actualBytes) {
            throw IOException(
                "Ukuran unduhan tidak sesuai: diharapkan $expectedBytes byte, diterima $actualBytes byte. " +
                    "Periksa koneksi lalu unduh ulang."
            )
        }
    }

    private fun isMpegAudio(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val second = header.unsigned(1)
        val third = header.unsigned(2)
        return header.unsigned(0) == 0xff && second and 0xe0 == 0xe0 &&
            (second shr 3) and 3 != 1 && // Reserved MPEG version.
            (second shr 1) and 3 == 1 && // Only Layer III is MP3; reject Layers I/II and ADTS.
            (third shr 4) in 1..14 &&
            (third shr 2) and 3 != 3 &&
            header.unsigned(3) and 3 != 2 // Reserved emphasis.
    }

    private fun isAdts(header: ByteArray): Boolean {
        if (header.size < 7 || header.unsigned(0) != 0xff) return false
        val second = header.unsigned(1)
        if (second and 0xf6 != 0xf0 || (header.unsigned(2) shr 2) and 15 > 12) return false
        val headerSize = if (second and 1 == 0) 9 else 7
        val frameSize = ((header.unsigned(3) and 3) shl 11) or
            (header.unsigned(4) shl 3) or (header.unsigned(5) shr 5)
        // The supplied bytes may be only a prefix of a frame, but must contain its header.
        return header.size >= headerSize && frameSize >= headerSize
    }

    private fun isWebM(header: ByteArray): Boolean {
        if (header.size < 5 || header.unsigned(0) != 0x1a || header.unsigned(1) != 0x45 ||
            header.unsigned(2) != 0xdf || header.unsigned(3) != 0xa3
        ) return false
        val size = readEbmlInteger(header, 4, stripMarker = true, maxBytes = 8) ?: return false
        if (size.value > header.size - size.nextOffset) return false
        val end = size.nextOffset + size.value.toInt()
        var offset = size.nextOffset
        var webm = false
        while (offset < end) {
            val id = readEbmlInteger(header, offset, stripMarker = false, maxBytes = 4) ?: return false
            val length = readEbmlInteger(header, id.nextOffset, stripMarker = true, maxBytes = 8)
                ?: return false
            if (length.nextOffset > end || length.value > end - length.nextOffset) return false
            if (id.value == 0x4282L) {
                if (length.value != 4L || !header.matches(length.nextOffset, "webm")) return false
                webm = true
            }
            offset = length.nextOffset + length.value.toInt()
        }
        return webm
    }

    private data class EbmlInteger(val value: Long, val nextOffset: Int)

    private fun readEbmlInteger(
        header: ByteArray,
        offset: Int,
        stripMarker: Boolean,
        maxBytes: Int
    ): EbmlInteger? {
        if (offset >= header.size) return null
        val first = header.unsigned(offset)
        var marker = 0x80
        var length = 1
        while (length <= maxBytes && first and marker == 0) {
            marker = marker shr 1
            length++
        }
        if (length > maxBytes || length > header.size - offset) return null
        var value = (if (stripMarker) first and (marker - 1) else first).toLong()
        for (index in 1 until length) {
            value = (value shl 8) or header.unsigned(offset + index).toLong()
        }
        // Unknown-sized EBML headers/elements cannot establish a bounded DocType.
        if (stripMarker && value == (1L shl (7 * length)) - 1) return null
        return EbmlInteger(value, offset + length)
    }

    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.matches(offset: Int, signature: String): Boolean =
        offset <= size && signature.length <= size - offset &&
            signature.indices.all { unsigned(offset + it) == signature[it].code }
}
