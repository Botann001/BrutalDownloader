package com.example.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class YouTubeStreamParserTest {

    @Test
    fun extractVideoIdHandlesStandardShortsLiveAndShareLinks() {
        assertEquals("14OBXAjgj7U", YouTubeStreamParser.extractVideoId("https://youtu.be/14OBXAjgj7U?si=EQ9zenwynF0svOhw"))
        assertEquals("14OBXAjgj7U", YouTubeStreamParser.extractVideoId("https://www.youtube.com/watch?v=14OBXAjgj7U&t=42s"))
        assertEquals("abc123XYZ", YouTubeStreamParser.extractVideoId("https://youtube.com/shorts/abc123XYZ?feature=share"))
        assertEquals("live999", YouTubeStreamParser.extractVideoId("https://www.youtube.com/live/live999?si=1"))
        assertNull(YouTubeStreamParser.extractVideoId("https://www.youtube.com/"))
    }

    @Test
    fun parseInvidiousResponseFallsBackToAdaptiveMuxWhenFormatStreamsIsEmpty() {
        val json = """
            {
              "title": "Sweet Boy - Malcolm Todd",
              "formatStreams": [],
              "adaptiveFormats": [
                {"itag": "140", "container": "m4a", "type": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 129000, "url": "https://rr.example/a"},
                {"itag": "136", "qualityLabel": "720p", "container": "mp4", "type": "video/mp4; codecs=\"avc1.4D401F\"", "bitrate": 1200000, "url": "https://rr.example/v720"},
                {"itag": "137", "qualityLabel": "1080p", "container": "mp4", "type": "video/mp4; codecs=\"avc1.640028\"", "bitrate": 2400000, "url": "https://rr.example/v1080"},
                {"itag": "399", "qualityLabel": "1080p", "type": "video/mp4; codecs=\"av01.0.08M.08\"", "bitrate": 1500000, "url": "https://rr.example/av1"}
              ]
            }
        """.trimIndent()

        val best = YouTubeStreamParser.parseInvidiousResponse(
            host = "https://yt.chocolatemoo53.com",
            videoId = "14OBXAjgj7U",
            jsonBody = json,
            isAudioOnly = false,
            targetHeight = null
        )
        assertEquals(1080, best.selectedHeight)
        assertEquals("https://yt.chocolatemoo53.com/latest_version?id=14OBXAjgj7U&itag=137&local=true", best.videoUrl)
        assertEquals("https://yt.chocolatemoo53.com/latest_version?id=14OBXAjgj7U&itag=140&local=true", best.audioUrl)

        val hd = YouTubeStreamParser.parseInvidiousResponse(
            host = "https://yt.chocolatemoo53.com",
            videoId = "14OBXAjgj7U",
            jsonBody = json,
            isAudioOnly = false,
            targetHeight = 720
        )
        assertEquals(720, hd.selectedHeight)
        assertEquals("https://yt.chocolatemoo53.com/latest_version?id=14OBXAjgj7U&itag=136&local=true", hd.videoUrl)

        val audio = YouTubeStreamParser.parseInvidiousResponse(
            host = "https://yt.chocolatemoo53.com",
            videoId = "14OBXAjgj7U",
            jsonBody = json,
            isAudioOnly = true,
            targetHeight = null
        )
        assertNull(audio.audioUrl)
        assertEquals("https://yt.chocolatemoo53.com/latest_version?id=14OBXAjgj7U&itag=140&local=true", audio.videoUrl)
    }

    @Test(expected = IOException::class)
    fun parseInvidiousResponseRejectsErrorPayload() {
        YouTubeStreamParser.parseInvidiousResponse(
            host = "https://yt.chocolatemoo53.com",
            videoId = "14OBXAjgj7U",
            jsonBody = """{"error": "Video unavailable"}""",
            isAudioOnly = false,
            targetHeight = null
        )
    }

    @Test
    fun selectStreamPrefersMuxedOverAdaptiveAtSameHeight() {
        val muxed = listOf(YouTubeCandidate(360, "https://cdn/muxed360", bitrate = 500_000))
        val adaptive = listOf(
            YouTubeCandidate(360, "https://cdn/adapt360", bitrate = 600_000),
            YouTubeCandidate(720, "https://cdn/adapt720", bitrate = 1_500_000)
        )
        val audio = listOf(YouTubeAudioCandidate("https://cdn/m4a", bitrate = 128_000, isM4a = true))

        val picked360 = YouTubeStreamParser.selectStream(muxed, adaptive, audio, isAudioOnly = false, targetHeight = 360)
        assertEquals("https://cdn/muxed360", picked360.videoUrl)
        assertNull(picked360.audioUrl)

        val pickedBest = YouTubeStreamParser.selectStream(muxed, adaptive, audio, isAudioOnly = false, targetHeight = null)
        assertEquals("https://cdn/adapt720", pickedBest.videoUrl)
        assertEquals("https://cdn/m4a", pickedBest.audioUrl)
        assertTrue(pickedBest.description.contains("720p"))
    }
}
