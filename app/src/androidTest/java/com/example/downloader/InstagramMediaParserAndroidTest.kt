package com.example.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstagramMediaParserAndroidTest {
    @Test
    fun parsesPhotoAndVideoWithExactSignedQuery() {
        val html = """
            <style>.download-items { display: block; }</style>
            <div class="download-items"><img src="https://thumbnail.example/preview">
            <a onclick="showAd();" href="https://d.rapidcdn.app/p?sig=a+b%2Fc%25&amp;x=1" class="button" title="Download Photo"><span>Download Photo</span></span></a></div>
            <a title='Download Video' href='https://cdn.example/v'>Download Video</a>
            <a href='https://ads.example/ad'>Download App</a>
        """.trimIndent()
        val items = InstagramMediaParser.parseDecodedHtml(html)
        assertEquals(2, items.size)
        assertEquals(InstagramMediaKind.PHOTO, items[0].kind)
        assertEquals("https://d.rapidcdn.app/p?sig=a+b%2Fc%25&x=1", items[0].url)
        assertEquals(InstagramMediaKind.VIDEO, items[1].kind)
    }

    @Test
    fun parsesJavascriptAssignmentAndLongPackedResponse() {
        val html = "<style>${"x".repeat(40000)}</style>" +
            """<a onclick="showAd();" href="https://d.rapidcdn.app/p?sig=a+b%2F&amp;x=1" title="Download Photo"><span>Download Photo</span></span></a>"""
        for (quote in listOf('\'', '"')) {
            val script = "document.getElementById(\"download-section\").innerHTML = ${jsString(html, quote)};"
            val decodedItems = InstagramMediaParser.parseDecodedHtml(script)
            assertEquals(1, decodedItems.size)
            val alphabet = "abcdefghi"
            val payload = buildString {
                for (byte in script.toByteArray(Charsets.UTF_8)) {
                    val value = (byte.toInt() and 0xff) + 7
                    value.toString(8).forEach { append(alphabet[it - '0']) }
                    append(alphabet[8])
                }
            }
            val packed = "eval(function(h,u,n,t,e,r){return h;}(${jsString(payload, quote)},0,${jsString(alphabet, quote)},7,8,0))"
            assertTrue(packed.length > 120000)
            val items = InstagramMediaParser.parseResponse(packed)
            assertEquals(decodedItems, items)
        }
    }

    @Test
    fun parsesLongQuotedDownloadSectionAssignments() {
        for (length in listOf(1000, 10000, 30000, 60000, 120000, 240000)) {
            for (quote in listOf('\'', '"')) {
                val html = "<style>${"x".repeat(length)}</style>" +
                    """<a href="https://cdn.example/p?sig=a+b%2F&amp;x=1" title="Download Photo">Download Photo</a>"""
                val script = "document.getElementById('download-section').innerHTML = ${jsString(html, quote)};"
                val items = InstagramMediaParser.parseResponse(script)
                assertEquals("length=$length, quote=$quote", 1, items.size)
                assertEquals("https://cdn.example/p?sig=a+b%2F&x=1", items[0].url)
                assertEquals(InstagramMediaKind.PHOTO, items[0].kind)
            }
        }
    }

    private fun jsString(value: String, quote: Char = '"'): String = quote + value.replace("\\", "\\\\")
        .replace(quote.toString(), "\\$quote").replace("\n", "\\n").replace("\r", "\\r") + quote
}
