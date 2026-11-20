package com.example.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstagramFallbackParserAndroidTest {
    private val source = "https://www.instagram.com/reel/Dfxh0HsN9ZN/"
    private val videoUrl = "https://cdn.example/video.mp4"

    @Test
    fun parsesVerifiedSchemaAndPreservesSignedUrlExactly() {
        val signed = "https://scontent.example/a%2Fb.mp4?sig=a+b%2Bc%2f%252F%3D&literal=%25&empty=&flag&quote='&n=7#fragment"
        val body = response(video(variant(signed)))
            .put("author", "Koi")
            .put("text", "Synthetic caption")
        assertEquals(listOf(InstagramMediaItem(signed, InstagramMediaKind.VIDEO)), parse(body))
    }

    @Test
    fun choosesFirstValidMp4VariantNotHighestBitrateOrThumbnail() {
        val item = video(
            variant("https://cdn.example/audio.m4a", "audio/mp4"),
            variant("http://cdn.example/insecure.mp4"),
            variant("https://user@cdn.example/credentials.mp4"),
            variant(videoUrl).put("bitrate", 100),
            variant("https://cdn.example/higher.mp4").put("bitrate", 999999)
        ).put("thumbnailUrl", "https://cdn.example/thumbnail.jpg")
        assertEquals(listOf(InstagramMediaItem(videoUrl, InstagramMediaKind.VIDEO)), parse(response(item)))
    }

    @Test
    fun preservesMediaOrderAndReturnsOneVariantPerItem() {
        val second = "https://cdn.example/second.mp4"
        val body = response(video(variant(videoUrl), variant(second)), video(variant(second)))
        assertEquals(
            listOf(
                InstagramMediaItem(videoUrl, InstagramMediaKind.VIDEO),
                InstagramMediaItem(second, InstagramMediaKind.VIDEO)
            ),
            parse(body)
        )
    }

    @Test
    fun matchesShortcodeAcrossSupportedInstagramUrls() {
        for (host in listOf("instagram.com", "www.instagram.com", "m.instagram.com")) {
            for (path in listOf("reel", "p", "tv")) {
                val actualSource = "https://$host/$path/Dfxh0HsN9ZN?utm_source=share#fragment"
                assertEquals(1, parse(response(video(variant(videoUrl))).put("tweetUrl", actualSource)).size)
            }
        }
        assertEquals(1, parse(response(video(variant(videoUrl))), "https://instagram.com/p/Dfxh0HsN9ZN/").size)
    }

    @Test
    fun rejectsHostileOrMalformedSourceAndExpectedUrls() {
        val badUrls = listOf(
            "https://www.instagram.com/reel/OTHER/",
            "https://instagram.com.evil.example/reel/Dfxh0HsN9ZN/",
            "https://evilinstagram.com/reel/Dfxh0HsN9ZN/",
            "https://evil.example/?url=$source",
            "https://instagram.com@evil.example/reel/Dfxh0HsN9ZN/",
            "https://user@www.instagram.com/reel/Dfxh0HsN9ZN/",
            "http://www.instagram.com/reel/Dfxh0HsN9ZN/",
            "//www.instagram.com/reel/Dfxh0HsN9ZN/",
            "https:///reel/Dfxh0HsN9ZN/",
            "https://www.instagram.com/stories/Dfxh0HsN9ZN/",
            "https://www.instagram.com/reel/Dfxh0HsN9ZN/extra/",
            "https://www.instagram.com/reel/%44fxh0HsN9ZN/",
            "https://www.instagram.com/reel/",
            "https://www.instagram.com/reel/Dfxh0HsN9ZN /",
            ""
        )
        for (url in badUrls) {
            expectIo { parse(response(video(variant(videoUrl))).put("tweetUrl", url)) }
            expectIo { parse(response(video(variant(videoUrl))), url) }
        }
    }

    @Test
    fun rejectsWrongOrMissingPlatformAndSourceTypes() {
        for (value in listOf<Any>("twitter", "Instagram", "", 1, true, JSONObject.NULL)) {
            expectIo { parse(response(video(variant(videoUrl))).put("platform", value)) }
        }
        expectIo { parse(response(video(variant(videoUrl))).apply { remove("platform") }) }
        for (value in listOf<Any>(1, true, JSONObject.NULL, JSONObject())) {
            expectIo { parse(response(video(variant(videoUrl))).put("tweetUrl", value)) }
        }
        expectIo { parse(response(video(variant(videoUrl))).apply { remove("tweetUrl") }) }
    }

    @Test
    fun rejectsUnsafeMediaUrlsAndNeverUsesThumbnail() {
        for (url in listOf(
            "/video.mp4", "//cdn.example/video.mp4", "http://cdn.example/video.mp4",
            "javascript:alert(1)", "data:video/mp4,abc", "ftp://cdn.example/video.mp4",
            "https:///video.mp4", "https://user:password@cdn.example/video.mp4",
            "https://cdn.example/a b.mp4", "https://cdn.example/%zz", ""
        )) {
            expectIo { parse(response(video(variant(url)).put("thumbnailUrl", videoUrl))) }
        }
        expectIo { parse(response(JSONObject().put("type", "video").put("thumbnailUrl", videoUrl))) }
    }

    @Test
    fun rejectsUnsupportedMixedMediaRatherThanReturningPartialResults() {
        for (type in listOf("image", "photo", "gif", "audio", "unknown", "")) {
            val unsupported = video(variant(videoUrl)).put("type", type)
            expectIo { parse(response(video(variant(videoUrl)), unsupported)) }
            expectIo { parse(response(unsupported, video(variant(videoUrl)))) }
        }
        expectIo { parse(response(video(variant(videoUrl)), JSONObject())) }
    }

    @Test
    fun rejectsEmptyOrMalformedMediaAndVariants() {
        for (value in listOf<Any>(JSONArray(), JSONObject.NULL, JSONObject(), "video", 1)) {
            expectIo { parse(response().put("media", value)) }
        }
        expectIo { parse(response().apply { remove("media") }) }
        expectIo { parse(response().put("media", JSONArray().put(JSONObject.NULL))) }
        expectIo { parse(response().put("media", JSONArray().put("video"))) }
        for (value in listOf<Any>(JSONArray(), JSONObject.NULL, JSONObject(), "variants", 1)) {
            expectIo { parse(response(JSONObject().put("type", "video").put("variants", value))) }
        }
        expectIo { parse(response(video().put("variants", JSONArray().put("not an object")))) }
        expectIo { parse(response(video(JSONObject()))) }
        expectIo { parse(response(video(variant(videoUrl, "video/webm")))) }
        expectIo { parse(response(video(JSONObject().put("contentType", "video/mp4").put("url", 123)))) }
    }

    @Test
    fun rejectsMalformedJsonAndTrailingPayload() {
        for (body in listOf("", " ", "{", "[]", "null", "true", "<html>Error</html>", "{\"media\":}")) {
            expectIo { InstagramFallbackParser.parseResponse(body, source) }
        }
        expectIo { InstagramFallbackParser.parseResponse(response(video(variant(videoUrl))).toString() + "{}", source) }
    }

    @Test
    fun enforcesEightMebibyteUtf8LimitIncludingMultibyteText() {
        val limit = 8 * 1024 * 1024
        val base = response(video(variant(videoUrl))).toString()
        val atLimit = base + " ".repeat(limit - base.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, InstagramFallbackParser.parseResponse(atLimit, source).size)
        expectIo { InstagramFallbackParser.parseResponse(atLimit + " ", source) }
        val multibyte = response(video(variant(videoUrl))).put("text", "\u00e9".repeat(limit / 2)).toString()
        assertTrue(multibyte.length < limit)
        assertTrue(multibyte.toByteArray(Charsets.UTF_8).size > limit)
        expectIo { InstagramFallbackParser.parseResponse(multibyte, source) }
    }

    private fun variant(url: String, contentType: String = "video/mp4") =
        JSONObject().put("url", url).put("contentType", contentType)

    private fun video(vararg variants: JSONObject) = JSONObject()
        .put("type", "video")
        .put("variants", JSONArray().apply { variants.forEach { put(it) } })

    private fun response(vararg media: JSONObject) = JSONObject()
        .put("tweetUrl", source)
        .put("platform", "instagram")
        .put("media", JSONArray().apply { media.forEach { put(it) } })

    private fun parse(body: JSONObject, expectedUrl: String = source) =
        InstagramFallbackParser.parseResponse(body.toString(), expectedUrl)

    private fun expectIo(block: () -> Unit) {
        try {
            block()
            fail("Expected actionable Indonesian IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().startsWith("Fallback Instagram:"))
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Coba"))
        }
    }
}
