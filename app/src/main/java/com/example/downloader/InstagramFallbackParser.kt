package com.example.downloader

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal object InstagramFallbackParser {
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private val instagramHosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val mediaPath = Regex("/(?:reel|p|tv)/([A-Za-z0-9_-]+)/?")

    fun parseResponse(body: String, expectedUrl: String): List<InstagramMediaItem> {
        if (body.length > MAX_RESPONSE_BYTES || body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw failure("Respons melebihi batas 8 MiB")
        }
        val expectedShortcode = shortcode(expectedUrl)
            ?: throw failure("Tautan Instagram yang diminta tidak valid")
        try {
            val tokener = JSONTokener(body)
            val response = tokener.nextValue() as? JSONObject
                ?: throw failure("Respons harus berupa objek JSON")
            if (tokener.nextClean() != '\u0000') {
                throw failure("Ada data tambahan setelah respons JSON")
            }
            if (response.opt("platform") != "instagram") {
                throw failure("Platform respons bukan instagram")
            }
            val source = response.opt("tweetUrl") as? String
                ?: throw failure("Respons tidak memiliki tweetUrl yang valid")
            if (shortcode(source) != expectedShortcode) {
                throw failure("Sumber respons tidak cocok dengan tautan Instagram yang diminta")
            }
            val media = response.optJSONArray("media")
                ?: throw failure("Daftar media tidak tersedia atau formatnya tidak valid")
            if (media.length() == 0) throw failure("Daftar media kosong")

            val items = ArrayList<InstagramMediaItem>()
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index)
                    ?: throw failure("Format media ke-${index + 1} tidak valid")
                if (item.opt("type") != "video") {
                    throw failure("Media ke-${index + 1} bukan video; fallback hanya mendukung video MP4")
                }
                val variants = item.optJSONArray("variants")
                    ?: throw failure("Daftar varian video ke-${index + 1} tidak valid")
                var selectedUrl: String? = null
                for (variantIndex in 0 until variants.length()) {
                    val variant = variants.optJSONObject(variantIndex)
                        ?: throw failure("Format varian video ke-${index + 1} tidak valid")
                    if (variant.opt("contentType") != "video/mp4") continue
                    val url = variant.opt("url") as? String ?: continue
                    if (selectedUrl == null && httpsUri(url) != null) selectedUrl = url
                }
                items.add(
                    InstagramMediaItem(
                        selectedUrl ?: throw failure("Video ke-${index + 1} tidak memiliki URL HTTPS MP4 yang valid"),
                        InstagramMediaKind.VIDEO
                    )
                )
            }
            return items
        } catch (cause: JSONException) {
            throw IOException("Fallback Instagram: Respons JSON rusak. Coba ulangi nanti.", cause)
        }
    }

    private fun shortcode(url: String): String? {
        val uri = httpsUri(url) ?: return null
        if (instagramHosts.none { it.equals(uri.host, ignoreCase = true) } ||
            (uri.port != -1 && uri.port != 443)
        ) return null
        return mediaPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    private fun httpsUri(url: String): URI? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null
        ) return null
        // Validate only: signed queries must not be normalized or decoded.
        return uri
    }

    private fun failure(detail: String) =
        IOException("Fallback Instagram: $detail. Coba tautan publik yang benar atau ulangi nanti.")
}
