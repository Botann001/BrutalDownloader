package com.example.downloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookMediaParserTest {
    @Test
    fun testParseFacebookSnapSaveResponse() {
        val file = File("C:/Users/oktam/.gemini/antigravity/brain/685e6576-1dfa-460e-a648-3f4d9415bcee/scratch/snapsave_fb.js")
        if (file.exists()) {
            val content = file.readText()
            val items = InstagramMediaParser.parseResponse(content)
            println("Facebook media items extracted: ${items.size}")
            for (item in items) {
                println("  - Kind: ${item.kind}, URL: ${item.url.take(80)}...")
            }
            assertTrue("Expected at least 1 Facebook video", items.isNotEmpty())
            assertEquals(InstagramMediaKind.VIDEO, items.first().kind)
            assertTrue("Expected video URL from rapidcdn or fbcdn", items.first().url.contains("rapidcdn.app") || items.first().url.contains("fbcdn.net"))
        }
    }
}
