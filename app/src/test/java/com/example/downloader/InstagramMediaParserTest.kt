package com.example.downloader

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramMediaParserTest {
    @Test
    fun extractsEightPhotoCarouselFromDownloadSectionAssignmentNotThumbnailsOrAds() {
        val urls = (1..8).map { "https://signed-image.example/$it?token=a+b%2Fc%3D&index=$it" }
        val html = buildString {
            for (url in urls) {
                append("""<div class="download-items"><img src="https://thumbnail.example/preview.jpg">""")
                append("""<a onclick="showAd();" href="${url.replace("&", "&amp;")}" title="Download Photo"><span>Download Photo</span></a></div>""")
            }
            append("""<a href="https://play.google.com/store/apps/details?id=app">Download App</a>""")
            append("""<a href="https://ads.example/click">Advertisement</a>""")
        }
        val script = "document.getElementById(\"download-section\").innerHTML = ${jsString(html)};"
        assertEquals(urls.map { photo(it) }, InstagramMediaParser.parseDecodedHtml(script))
        assertEquals(urls.map { photo(it) }, InstagramMediaParser.parseResponse(pack(script)))
    }

    @Test
    fun parsesLongPackedResponseAndLongJavascriptStringWithBothQuotes() {
        val url = "https://d.rapidcdn.app/photo?sig=a+b%2Fc%25&x=1"
        val html = "<style>${"x".repeat(120000)}</style>" +
            """<a onclick="showAd();" href="${url.replace("&", "&amp;")}" title="Download Photo"><span>Download Photo</span></span></a>"""
        for (quote in listOf('\'', '"')) {
            val script = "document.getElementById('download-section').innerHTML = ${jsString(html, quote)};"
            assertEquals(listOf(photo(url)), InstagramMediaParser.parseDecodedHtml(script))
            val response = pack(script, quote = quote)
            assertTrue(response.length > 120000)
            assertEquals(listOf(photo(url)), InstagramMediaParser.parseResponse(response))
        }
    }

    @Test
    fun handlesAttributeQuotesOrderCaseNewlinesAndNestedText() {
        val html = """
            <A
                class='button > icon'
                TITLE = 'Download Photo'
                data-href='https://ads.example/not-the-href'
                HREF = 'https://cdn.example/photo?x=1&amp;y=2'>
                <span>Download Photo</span>
            </A>
            <a href="https://cdn.example/video" onclick="showAd();" title="Download Video">
                <i class='icon'></i><span>Download</span>
                <span>Video</span>
            </a>
            <a href=https://cdn.example/unquoted title='download photo'>Save</a>
            <a title='Save original' href='https://cdn.example/text-only'><span>Download&nbsp;Video</span></a>
            <a href='https://cdn.example/title-only' title='Download Photo'></a>
        """.trimIndent()
        assertEquals(
            listOf(
                photo("https://cdn.example/photo?x=1&y=2"),
                video("https://cdn.example/video"),
                photo("https://cdn.example/unquoted"),
                video("https://cdn.example/text-only"),
                photo("https://cdn.example/title-only")
            ),
            InstagramMediaParser.parseDecodedHtml(html)
        )
    }

    @Test
    fun preservesExactSignedQueryWithoutPercentOrPlusDecoding() {
        val expected = "https://cdn.example/a%2Fb.jpg?sig=a+b%2Bc%2f%252F%3D&literal=%25&empty=&flag&quote='&n=7#fragment"
        val html = """<a href="https://cdn.example/a%2Fb.jpg?sig=a+b%2Bc%2f%252F%3D&amp;literal=%25&#38;empty=&#x26;flag&amp;quote=&apos;&amp;n=7#fragment" title="Download Photo">Download Photo</a>"""
        assertEquals(listOf(photo(expected)), InstagramMediaParser.parseResponse(html))
        assertEquals(listOf(photo(expected)), InstagramMediaParser.parseResponse(pack(html)))
    }

    @Test
    fun decodesJavascriptQuotesSlashesHexUnicodeAndLineContinuationsOnlyOnce() {
        val script = """
            document.getElementById('download-section').innerHTML = "<a title=\"Download Photo\" \
            href=\"https:\/\/cdn.example\/p?sig=a+b%2F\u0026x=1\x26y=2\">Download Photo<\/a>";
        """.trimIndent()
        val expected = listOf(photo("https://cdn.example/p?sig=a+b%2F&x=1&y=2"))
        assertEquals(expected, InstagramMediaParser.parseDecodedHtml(script))
        val singleQuoted = """document.getElementById("download-section").innerHTML = '<a href=\'https:\/\/cdn.example\/video\' title=\'Download Video\'>Download Video<\/a>';"""
        assertEquals(listOf(video("https://cdn.example/video")), InstagramMediaParser.parseResponse(singleQuoted))
    }

    @Test
    fun acceptsEscapedFragmentWithoutAssignment() {
        val escaped = """<a href=\"https:\/\/cdn.example\/p?sig=a+b%2F&amp;x=1\" title=\"Download Photo\"><span>Download Photo</span><\/a>"""
        assertEquals(
            listOf(photo("https://cdn.example/p?sig=a+b%2F&x=1")),
            InstagramMediaParser.parseDecodedHtml(escaped)
        )
    }

    @Test
    fun preservesMixedCarouselOrderAndFirstTypeWhileDeduplicatingDecodedUrls() {
        val html = """
            <a title='Download Video' href='https://cdn.example/2'>Download Video</a>
            <a title='Download Photo' href='https://cdn.example/1?a=1&amp;b=2'>Download Photo</a>
            <a title='Download Photo' href='https://cdn.example/2'>Download Photo</a>
            <a title='Download Photo' href='https://cdn.example/1?a=1&#38;b=2'>Download Photo</a>
            <a title='Download Video' href='https://cdn.example/3'>Download Video</a>
        """.trimIndent()
        assertEquals(
            listOf(video("https://cdn.example/2"), photo("https://cdn.example/1?a=1&b=2"), video("https://cdn.example/3")),
            InstagramMediaParser.parseDecodedHtml(html)
        )
    }

    @Test
    fun ignoresUnlabeledImagesNavigationAppAdsAndLookalikeAttributes() {
        val html = """
            <img src='https://cdn.example/thumbnail' title='Download Photo'>
            <a href='/'>Home</a>
            <a href='https://ads.example/ad'>Advertisement</a>
            <a href='https://play.google.com/store/apps/details?id=app'>Download App</a>
            <a href='https://cdn.example/preview.jpg'><img src='https://cdn.example/thumbnail'></a>
            <a data-href='https://cdn.example/fake' title='Download Photo'>Download Photo</a>
            <a href='https://ads.example/words'>How to Download Photo</a>
            <a href='https://ads.example/words' title='Download Video App'>Install</a>
            <a href='https://cdn.example/conflict' title='Download Photo'>Download Video</a>
            <!-- <a href='https://ads.example/comment' title='Download Photo'>Download Photo</a> -->
            <script>var fake = "<a href='https://ads.example/script'>Download Video</a>";</script>
            <a href='https://cdn.example/actual'><span>Download Video</span></a>
        """.trimIndent()
        assertEquals(listOf(video("https://cdn.example/actual")), InstagramMediaParser.parseResponse(html))
    }

    @Test
    fun assignmentScopesParsingToDownloadSection() {
        val html = "<a href='https://cdn.example/photo' title='Download Photo'>Download Photo</a>"
        val response = "<a href='https://ads.example/outside'>Download Video</a>" +
            "document.getElementById('download-section').innerHTML = ${jsString(html)};"
        assertEquals(listOf(photo("https://cdn.example/photo")), InstagramMediaParser.parseResponse(response))
    }

    @Test
    fun rejectsUnsafeRelativeMalformedAndNonNetworkHrefs() {
        for (url in listOf("/photo.jpg", "//cdn.example/p", "javascript:alert(1)", "data:image/jpeg,x", "ftp://cdn.example/p", "https:///p", "https://cdn.example/a b", "https://user@cdn.example/p", "https://cdn.example/%zz")) {
            expectIo { InstagramMediaParser.parseDecodedHtml("<a href='$url'>Download Photo</a>") }
        }
    }

    @Test
    fun rejectsEmptyErrorMalformedAndUnrecognizedResponsesWithActionableFailure() {
        val responses = listOf(
            "", "   ", "{\"error\":\"private account\"}", "<html>Login required</html>",
            "<a href='https://cdn.example/file.mp4'>Download</a>",
            "<a href='https://cdn.example/p' title='Download Photo'>",
            "eval(function(h,u,n,t,e,r){}(\"broken\"))",
            "document.getElementById('download-section').innerHTML = '<p>Private post</p>';"
        )
        for (response in responses) {
            val error = expectIo { InstagramMediaParser.parseResponse(response) }
            assertTrue(error.message.orEmpty().contains("Download Photo/Video"))
            assertTrue(error.message.orEmpty().contains("public"))
        }
    }

    @Test
    fun obfuscationRoundTripsAllSupportedBasesAndBothJavascriptQuotes() {
        val url = "https://cdn.example/photo?sig=a+b%2Fc%25&caption=café😀"
        val html = "<a title='Download Photo' href='$url'>Download Photo</a>"
        for (base in 2..10) {
            for (quote in listOf('\'', '"')) {
                val response = pack(html, base = base, offset = 137, quote = quote)
                assertEquals("base=$base, quote=$quote", listOf(photo(url)), InstagramMediaParser.parseResponse(response))
            }
        }
    }

    @Test
    fun strictUtf8MatchesDecodeURIComponentEscapeInsteadOfUrlDecoder() {
        val html = "<a title='Download Photo' href='https://cdn.example/p?q=é日本😀+%C3%A9%25'>Download Photo</a>"
        assertEquals(
            listOf(photo("https://cdn.example/p?q=é日本😀+%C3%A9%25")),
            InstagramMediaParser.parseResponse(pack(html))
        )
        val error = expectIo { InstagramMediaParser.parseResponse(packBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertTrue(error.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun rejectsInvalidObfuscationParametersRatherThanFallingBackToLinks() {
        val invalid = listOf(
            packedRaw("af", base = "0"),
            packedRaw("af", base = "1"),
            packedRaw("af", base = "11"),
            packedRaw("af", base = "99999999999999999999"),
            packedRaw("af", alphabet = "abc"),
            packedRaw("af", alphabet = "aaaaaa"),
            packedRaw("af", offset = "99999999999999999999"),
            packedRaw("af", u = "99999999999999999999"),
            packedRaw("af", r = "99999999999999999999")
        )
        for (response in invalid) {
            val error = expectIo {
                InstagramMediaParser.parseResponse(response + "<a href='https://cdn.example/p'>Download Photo</a>")
            }
            assertTrue(error.message.orEmpty().contains("SnapSave"))
        }
    }

    @Test
    fun rejectsInvalidTruncatedEmptyOverflowingAndOutOfRangeSegments() {
        val invalid = listOf(
            "" to "Empty",
            "f" to "Empty",
            "zف" to "digit",
            "a" to "delimiter",
            "e".repeat(32) + "f" to "integer",
            "a".repeat(33) + "f" to "integer",
            encodeValue(256, 5) to "range",
            "af" to "range" // Zero minus the default offset is negative.
        )
        for ((payload, message) in invalid) {
            val offset = if (payload == "af") "7" else "0"
            val error = expectIo { InstagramMediaParser.parseResponse(packedRaw(payload, offset = offset)) }
            assertTrue("${error.message} should contain $message", error.message.orEmpty().contains(message))
        }
    }

    @Test
    fun rejectsMalformedJavascriptEscapesWithIoException() {
        for (escape in listOf("\\xZZ", "\\u00Q0", "\\x1", "\\u123")) {
            val script = "document.getElementById('download-section').innerHTML = \"$escape\";"
            val error = expectIo { InstagramMediaParser.parseDecodedHtml(script) }
            assertTrue(error.message.orEmpty().contains("escape"))
        }
    }

    @Test
    fun boundsInputAndDecodedPayloadSizes() {
        val error = expectIo { InstagramMediaParser.parseResponse(" ".repeat(8 * 1024 * 1024 + 1)) }
        assertTrue(error.message.orEmpty().contains("size limit"))
        // Base two, zero offset: a byte of zero is encoded as "ac".
        val tooManyBytes = packedRaw("ac".repeat(2 * 1024 * 1024 + 1), base = "2", offset = "0")
        val decodedError = expectIo { InstagramMediaParser.parseResponse(tooManyBytes) }
        assertTrue(decodedError.message.orEmpty().contains("size limit"))
    }

    private fun photo(url: String) = InstagramMediaItem(url, InstagramMediaKind.PHOTO)
    private fun video(url: String) = InstagramMediaItem(url, InstagramMediaKind.VIDEO)

    private val alphabet = "abcdefghijk"

    private fun pack(text: String, base: Int = 5, offset: Int = 7, quote: Char = '"'): String =
        packBytes(text.toByteArray(Charsets.UTF_8), base, offset, quote)

    private fun packBytes(bytes: ByteArray, base: Int = 5, offset: Int = 7, quote: Char = '"'): String {
        val payload = buildString {
            for (byte in bytes) append(encodeValue((byte.toInt() and 0xff) + offset, base))
        }
        return packedRaw(payload, base = base.toString(), offset = offset.toString(), quote = quote)
    }

    private fun encodeValue(value: Int, base: Int): String =
        value.toString(base).map { alphabet[it - '0'] }.joinToString("") + alphabet[base]

    private fun packedRaw(
        payload: String,
        alphabet: String = this.alphabet,
        base: String = "5",
        offset: String = "7",
        u: String = "0",
        r: String = "0",
        quote: Char = '"'
    ): String = "eval(function(h,u,n,t,e,r){return h;}(\n" +
        "${jsString(payload, quote)}, $u, ${jsString(alphabet, quote)}, $offset, $base, $r\n))"

    private fun jsString(value: String, quote: Char = '"'): String = buildString {
        append(quote)
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                quote -> append('\\').append(character)
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append(quote)
    }

    private fun expectIo(action: () -> Unit): IOException {
        try {
            action()
        } catch (expected: IOException) {
            assertTrue("Failure must be actionable", !expected.message.isNullOrBlank())
            return expected
        }
        throw AssertionError("Expected IOException")
    }
}
