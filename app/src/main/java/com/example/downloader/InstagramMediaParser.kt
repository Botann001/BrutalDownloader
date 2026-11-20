package com.example.downloader

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal enum class InstagramMediaKind { PHOTO, VIDEO }

internal data class InstagramMediaItem(val url: String, val kind: InstagramMediaKind)

internal object InstagramMediaParser {
    private const val MAX_RESPONSE_LENGTH = 8 * 1024 * 1024
    private const val MAX_DECODED_LENGTH = 2 * 1024 * 1024
    // Consume ordinary characters in runs: per-character alternation silently fails
    // on long resolver payloads in Android 8.1's regex engine.
    private const val JS_STRING = """(?:"[^"\\]*(?:\\[\s\S][^"\\]*)*"|'[^'\\]*(?:\\[\s\S][^'\\]*)*')"""
    private val packedCall = Regex(
        "\\}\\s*\\(\\s*($JS_STRING)\\s*,\\s*(-?\\d+)\\s*,\\s*($JS_STRING)" +
            "\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)\\s*\\)"
    )
    private val htmlAssignment = Regex(
        """document\s*\.\s*getElementById\s*\(\s*(['"])download-section\1\s*\)\s*\.\s*innerHTML\s*=\s*($JS_STRING)"""
    )
    private val anchors = Regex(
        """<a\b((?:[^"'<>]|"[^"]*"|'[^']*')*)>([\s\S]*?)</a\s*>""",
        RegexOption.IGNORE_CASE
    )
    private val attributes = Regex(
        """([^\s"'=<>`]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?"""
    )
    private val comments = Regex("""<!--[\s\S]*?-->""")
    private val scripts = Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""<[^>]*>""")
    private val whitespace = Regex("[\\s\u00a0]+")
    private val entities = Regex("""&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);""")

    fun parseResponse(body: String): List<InstagramMediaItem> {
        checkLength(body, MAX_RESPONSE_LENGTH, "response")
        val packed = packedCall.find(body) ?: return parseDecodedHtml(body)
        fun integer(group: Int, name: String): Int = packed.groupValues[group].toIntOrNull()
            ?: throw failure("Invalid SnapSave $name; resolver format may have changed")

        val payload = decodeJsEscapes(packed.groupValues[1].drop(1).dropLast(1))
        integer(2, "parameter u") // Present in the wrapper, but unused by its decoding algorithm.
        val alphabet = decodeJsEscapes(packed.groupValues[3].drop(1).dropLast(1))
        val offset = integer(4, "offset")
        val base = integer(5, "base")
        integer(6, "parameter r")
        return parseDecodedHtml(decodeSnapSave(payload, alphabet, offset, base))
    }

    fun parseDecodedHtml(decoded: String): List<InstagramMediaItem> {
        checkLength(decoded, MAX_RESPONSE_LENGTH, "decoded response")
        val assignments = htmlAssignment.findAll(decoded).toList()
        val fragments = if (assignments.isNotEmpty()) {
            assignments.map { decodeJsEscapes(it.groupValues[2].drop(1).dropLast(1)) }
        } else {
            listOf(decoded)
        }
        val items = linkedMapOf<String, InstagramMediaItem>()
        for (fragment in fragments) collectAnchors(fragment, items)
        // Some resolver variants supply just the escaped fragment, without the assignment.
        if (items.isEmpty() && assignments.isEmpty() && (decoded.contains("\\\"") || decoded.contains("\\'"))) {
            collectAnchors(decodeJsEscapes(decoded), items)
        }
        if (items.isEmpty()) {
            throw failure("No Instagram Download Photo/Video links found; check that the post is public and retry")
        }
        return items.values.toList()
    }

    private fun collectAnchors(html: String, items: MutableMap<String, InstagramMediaItem>) {
        val visibleHtml = scripts.replace(comments.replace(html, ""), "")
        for (anchor in anchors.findAll(visibleHtml)) {
            val attrs = linkedMapOf<String, String>()
            for (attribute in attributes.findAll(anchor.groupValues[1])) {
                val name = attribute.groupValues[1].lowercase(Locale.ROOT)
                val value = (2..4).firstNotNullOfOrNull { attribute.groups[it]?.value }.orEmpty()
                attrs.putIfAbsent(name, value)
            }
            val titleKind = kind(attrs["title"].orEmpty())
            val textKind = kind(tags.replace(anchor.groupValues[2], " "))
            if (titleKind != null && textKind != null && titleKind != textKind) continue
            val mediaKind = titleKind ?: textKind ?: continue
            val url = decodeEntities(attrs["href"] ?: continue)
            // URI validates only: never normalize or form-decode a signed media URL.
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                continue
            }
            if (!(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) ||
                uri.host.isNullOrEmpty() || uri.rawUserInfo != null
            ) continue
            items.putIfAbsent(url, InstagramMediaItem(url, mediaKind))
        }
    }

    private fun kind(label: String): InstagramMediaKind? =
        when (whitespace.replace(decodeEntities(label), " ").trim().lowercase(Locale.ROOT)) {
            "download photo" -> InstagramMediaKind.PHOTO
            "download video" -> InstagramMediaKind.VIDEO
            else -> null
        }

    private fun decodeSnapSave(payload: String, alphabet: String, offset: Int, base: Int): String {
        // The wrapper substitutes alphabet characters with decimal digits before base conversion.
        if (base !in 2..10 || alphabet.length <= base || alphabet.length > 64 ||
            alphabet.toSet().size != alphabet.length
        ) throw failure("Invalid SnapSave base/alphabet; resolver format may have changed")
        if (payload.isEmpty()) throw failure("Empty SnapSave payload; retry the resolver")
        val delimiter = alphabet[base]
        val bytes = ByteArray(minOf(payload.length, MAX_DECODED_LENGTH))
        var count = 0
        var value = 0L
        var digits = 0
        for (character in payload) {
            if (character == delimiter) {
                if (digits == 0) throw failure("Empty SnapSave character segment; retry the resolver")
                val byteValue = value - offset.toLong()
                if (byteValue !in 0L..255L) throw failure("SnapSave decoded byte is out of range; resolver format may have changed")
                if (count == bytes.size) throw failure("SnapSave decoded payload exceeds the size limit")
                bytes[count++] = byteValue.toByte()
                value = 0
                digits = 0
            } else {
                val digit = alphabet.indexOf(character)
                if (digit !in 0 until base) throw failure("Invalid digit in SnapSave payload; retry the resolver")
                if (++digits > 32 || value > (Int.MAX_VALUE.toLong() + 255 - digit) / base) {
                    throw failure("SnapSave character segment overflows the integer limit")
                }
                value = value * base + digit
            }
        }
        if (digits != 0) throw failure("Truncated SnapSave payload: missing character delimiter; retry the resolver")
        // decodeURIComponent(escape(r)) interprets the character values as UTF-8 bytes,
        // not as form data: literal '+' and percent escapes must survive unchanged.
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        } catch (cause: CharacterCodingException) {
            throw IOException("Invalid UTF-8 in SnapSave payload; retry the resolver", cause)
        }
    }

    private fun decodeJsEscapes(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character != '\\') {
                append(character)
                continue
            }
            if (index == value.length) throw failure("Truncated JavaScript escape in resolver response")
            when (val escaped = value[index++]) {
                '\n' -> Unit // JavaScript line continuation.
                '\r' -> if (index < value.length && value[index] == '\n') index++
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'v' -> append('\u000b')
                '0' -> append('\u0000')
                'x', 'u' -> {
                    val length = if (escaped == 'x') 2 else 4
                    if (index + length > value.length) throw failure("Truncated JavaScript hex escape in resolver response")
                    val hex = value.substring(index, index + length)
                    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                        throw failure("Invalid JavaScript hex escape in resolver response")
                    }
                    val code = hex.toInt(16)
                    append(code.toChar())
                    index += length
                }
                else -> append(escaped) // Includes escaped quotes, backslashes and slashes.
            }
        }
    }

    private fun decodeEntities(value: String): String = entities.replace(value) { match ->
        val entity = match.groupValues[1]
        when (entity) {
            "amp", "AMP" -> "&"
            "quot", "QUOT" -> "\""
            "apos" -> "'"
            "lt", "LT" -> "<"
            "gt", "GT" -> ">"
            "nbsp" -> "\u00a0"
            else -> {
                val codePoint = when {
                    entity.startsWith("#x", true) -> entity.substring(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                    else -> null
                }
                if (codePoint != null && codePoint in 1..0x10ffff && codePoint !in 0xd800..0xdfff) {
                    String(Character.toChars(codePoint))
                } else {
                    match.value
                }
            }
        }
    }

    private fun checkLength(value: String, limit: Int, name: String) {
        if (value.length > limit) throw failure("Instagram $name exceeds the size limit; retry the resolver")
    }

    private fun failure(message: String) = IOException(message)
}
