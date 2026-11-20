package com.example.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

internal object ImageMediaValidator {
    fun validate(context: Context, uri: Uri, format: DownloadFormat): String {
        val expectedExtension = when (format.mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
        if (expectedExtension == null || format.extension != expectedExtension) {
            throw IOException("Format foto tidak didukung. Gunakan foto JPG, PNG, WebP, atau GIF.")
        }

        try {
            if (format.mimeType == "image/png") validatePng(context, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val headerStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Foto tidak dapat dibuka untuk diperiksa.")
            headerStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != format.mimeType) {
                throw IOException("Data foto rusak atau tidak sesuai dengan format ${format.extension.uppercase()}.")
            }

            var sampleSize = 1
            // Use a ceiling bound so odd dimensions cannot decode above the limit.
            while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > 2048L * sampleSize) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val pixelStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Foto tidak dapat dibuka untuk validasi.")
            val bitmap = pixelStream.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("Foto tidak dapat didekode. File mungkin tidak lengkap atau tidak didukung perangkat.")
            bitmap.recycle()
            return "Dimensi foto: ${bounds.outWidth} × ${bounds.outHeight}"
        } catch (failure: IOException) {
            throw IOException(
                "Foto tidak dapat divalidasi: ${failure.message} Unduh ulang dari tautan foto langsung, lalu coba lagi.",
                failure
            )
        } catch (failure: SecurityException) {
            throw IOException("Foto tidak dapat diakses. Pilih kembali file atau unduh ulang, lalu coba lagi.", failure)
        }
    }

    private fun validatePng(context: Context, uri: Uri) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Foto PNG tidak dapat dibuka untuk diperiksa.")
        try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                val signature = ByteArray(8)
                input.readFully(signature)
                if (!signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
                    throw IOException("Tanda pengenal foto PNG tidak valid.")
                }

                val typeBytes = ByteArray(4)
                val buffer = ByteArray(8192)
                val crc = CRC32()
                var firstChunk = true
                var hasImageData = false
                while (true) {
                    val length = input.readInt().toLong() and 0xffffffffL
                    if (length > 0x7fffffffL) {
                        throw IOException("Panjang chunk foto PNG melebihi batas format.")
                    }
                    input.readFully(typeBytes)
                    val type = String(typeBytes, Charsets.US_ASCII)
                    if (firstChunk && type != "IHDR") {
                        throw IOException("Foto PNG harus diawali chunk IHDR.")
                    }
                    when (type) {
                        "IHDR" -> if (!firstChunk || length != 13L) {
                            throw IOException("Chunk IHDR foto PNG tidak valid.")
                        }
                        "IDAT" -> if (length > 0L) hasImageData = true
                        "IEND" -> if (length != 0L || !hasImageData) {
                            throw IOException("Foto PNG tidak memiliki data gambar atau chunk IEND yang valid.")
                        }
                    }
                    crc.reset()
                    crc.update(typeBytes)
                    // Never trust a declared length for allocation or InputStream.available().
                    var remaining = length
                    while (remaining > 0L) {
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        input.readFully(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        remaining -= count
                    }
                    val expectedCrc = input.readInt().toLong() and 0xffffffffL
                    if (crc.value != expectedCrc) {
                        throw IOException("Pemeriksaan CRC chunk $type foto PNG gagal; data rusak.")
                    }
                    if (type == "IEND") {
                        if (input.read() != -1) {
                            throw IOException("Terdapat data setelah chunk IEND foto PNG.")
                        }
                        return
                    }
                    firstChunk = false
                }
            }
        } catch (failure: EOFException) {
            throw IOException("Foto PNG terpotong atau chunk IEND tidak ditemukan. Unduh ulang foto.", failure)
        }
    }
}
