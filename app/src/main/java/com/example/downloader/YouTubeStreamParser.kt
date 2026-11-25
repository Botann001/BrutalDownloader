package com.example.downloader

import org.json.JSONObject
import java.io.IOException

internal data class YouTubeCandidate(
    val height: Int,
    val url: String,
    val bitrate: Long = 0L,
    val isH264: Boolean = true
)

internal data class YouTubeAudioCandidate(
    val url: String,
    val bitrate: Long,
    val isM4a: Boolean
)

internal data class YouTubeResolvedStream(
    val videoUrl: String,
    val audioUrl: String? = null,
    val selectedHeight: Int? = null,
    val description: String
)

internal object YouTubeStreamParser {
    private val heightRegex = Regex("(\\d+)p")

    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.contains("youtu.be/") ->
                trimmed.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").ifBlank { null }
            trimmed.contains("/shorts/") ->
                trimmed.substringAfter("/shorts/").substringBefore("?").substringBefore("/").ifBlank { null }
            trimmed.contains("/live/") ->
                trimmed.substringAfter("/live/").substringBefore("?").substringBefore("/").ifBlank { null }
            trimmed.contains("/embed/") ->
                trimmed.substringAfter("/embed/").substringBefore("?").substringBefore("/").ifBlank { null }
            trimmed.contains("v=") ->
                trimmed.substringAfter("v=").substringBefore("&").substringBefore("#").ifBlank { null }
            else -> null
        }
    }

    fun selectStream(
        muxedVideos: List<YouTubeCandidate>,
        adaptiveVideos: List<YouTubeCandidate>,
        audioStreams: List<YouTubeAudioCandidate>,
        isAudioOnly: Boolean,
        targetHeight: Int?
    ): YouTubeResolvedStream {
        if (isAudioOnly) {
            val bestAudio = audioStreams.maxWithOrNull(
                compareBy<YouTubeAudioCandidate> { it.isM4a }.thenBy { it.bitrate }
            ) ?: throw IOException("Audio terpisah tidak tersedia; video tidak akan disimpan sebagai MP3.")
            return YouTubeResolvedStream(
                videoUrl = bestAudio.url,
                description = "Audio asli; bukan konversi MP3."
            )
        }

        val bestM4a = audioStreams.filter { it.isM4a }.maxByOrNull { it.bitrate }
        val usableAdaptive = if (bestM4a != null) adaptiveVideos else emptyList()
        val availableHeights = (muxedVideos.map { it.height } + usableAdaptive.map { it.height }).distinct()
        if (availableHeights.isEmpty()) {
            throw IOException("Tidak ada stream video MP4 yang kompatibel tersedia.")
        }

        val selectedHeight = MediaDownloadPolicy.selectVideoHeight(availableHeights, targetHeight)
        val muxedMatch = muxedVideos.filter { it.height == selectedHeight }.maxByOrNull { it.bitrate }
        if (muxedMatch != null) {
            val description = if (targetHeight != null && selectedHeight != targetHeight) {
                "${targetHeight}p tidak tersedia; memilih ${selectedHeight}p (audio termasuk)."
            } else {
                "Memilih ${selectedHeight}p (MP4 langsung); resolusi aktual akan diverifikasi."
            }
            return YouTubeResolvedStream(
                videoUrl = muxedMatch.url,
                selectedHeight = selectedHeight,
                description = description
            )
        }

        val adaptiveCandidates = usableAdaptive.filter { it.height == selectedHeight }
        val adaptiveMatch = adaptiveCandidates
            .filter { it.isH264 }
            .maxByOrNull { it.bitrate }
            ?: adaptiveCandidates.maxByOrNull { it.bitrate }
            ?: throw IOException("Stream video ${selectedHeight}p tidak tersedia.")

        val audioMatch = bestM4a
            ?: throw IOException("Stream audio M4A untuk penggabungan tidak tersedia.")

        val description = if (targetHeight != null && selectedHeight != targetHeight) {
            "${targetHeight}p tidak tersedia; memilih ${selectedHeight}p + audio (mux MP4)."
        } else {
            "Memilih ${selectedHeight}p + audio M4A (gabung MP4 otomatis)."
        }
        return YouTubeResolvedStream(
            videoUrl = adaptiveMatch.url,
            audioUrl = audioMatch.url,
            selectedHeight = selectedHeight,
            description = description
        )
    }

    fun parseInvidiousResponse(
        host: String,
        videoId: String,
        jsonBody: String,
        isAudioOnly: Boolean,
        targetHeight: Int?
    ): YouTubeResolvedStream {
        val json = JSONObject(jsonBody)
        val errorMsg = json.optString("error")
        if (errorMsg.isNotBlank()) {
            throw IOException("Invidious ($host): $errorMsg")
        }

        val cleanHost = host.trimEnd('/')
        fun streamUrl(item: JSONObject): String {
            val itag = item.optString("itag").trim()
            return if (itag.isNotEmpty()) {
                "$cleanHost/latest_version?id=$videoId&itag=$itag&local=true"
            } else {
                item.optString("url")
            }
        }

        val muxedVideos = mutableListOf<YouTubeCandidate>()
        val formatStreams = json.optJSONArray("formatStreams")
        if (formatStreams != null) {
            for (i in 0 until formatStreams.length()) {
                val item = formatStreams.getJSONObject(i)
                val type = item.optString("type").lowercase()
                val container = item.optString("container").lowercase()
                val height = parseHeight(item)
                val url = streamUrl(item)
                if (height > 0 && url.isNotBlank() && (type.startsWith("video/mp4") || container == "mp4")) {
                    muxedVideos.add(YouTubeCandidate(height, url, item.optLong("bitrate", 0L), isH264 = true))
                }
            }
        }

        val adaptiveVideos = mutableListOf<YouTubeCandidate>()
        val audioStreams = mutableListOf<YouTubeAudioCandidate>()
        val adaptiveFormats = json.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null) {
            for (i in 0 until adaptiveFormats.length()) {
                val item = adaptiveFormats.getJSONObject(i)
                val type = item.optString("type").lowercase()
                val container = item.optString("container").lowercase()
                val url = streamUrl(item)
                if (url.isBlank()) continue
                val bitrate = item.optLong("bitrate", 0L)

                if (type.startsWith("audio/mp4") || type.startsWith("audio/webm") || container == "m4a") {
                    val isM4a = type.startsWith("audio/mp4") || container == "m4a"
                    audioStreams.add(YouTubeAudioCandidate(url, bitrate, isM4a))
                } else if (type.startsWith("video/mp4") || container == "mp4") {
                    val height = parseHeight(item)
                    if (height > 0) {
                        val isAv1 = type.contains("av01")
                        val isH264 = type.contains("avc1") || (!isAv1 && !type.contains("vp09") && !type.contains("vp9"))
                        if (!isAv1) {
                            adaptiveVideos.add(YouTubeCandidate(height, url, bitrate, isH264 = isH264))
                        }
                    }
                }
            }
        }

        return selectStream(muxedVideos, adaptiveVideos, audioStreams, isAudioOnly, targetHeight)
    }

    private fun parseHeight(item: JSONObject): Int {
        return heightRegex.find(item.optString("qualityLabel"))
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: item.optInt("height", 0)
    }
}
