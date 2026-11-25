package com.example.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.schabi.newpipe.extractor.MediaFormat as NewPipeMediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var urlEditText: EditText
    private lateinit var pasteButton: Button
    private lateinit var clearButton: Button
    private lateinit var sourceSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var downloadButton: Button
    private lateinit var downloadAudioButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var percentTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var previewContainer: LinearLayout
    private lateinit var extractorWebView: WebView
    private lateinit var mainScrollView: ScrollView
    private lateinit var impactBanner: View
    private lateinit var bannerTitleTextView: TextView
    private lateinit var bannerDescTextView: TextView
    private lateinit var playMediaButton: Button
    private lateinit var closeBannerButton: View
    private var lastSavedMedia: SavedMedia? = null
    private val dismissBannerRunnable = Runnable { dismissBanner() }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingAudioOnly: Boolean? = null
    @Volatile
    private var newPipeInitialized = false

    private val platformOptions = arrayOf(
        "AUTO-DETECT",
        "TIKTOK",
        "INSTAGRAM",
        "YOUTUBE",
        "TWITTER / X",
        "FACEBOOK",
        "DIRECT LINK"
    )

    private val resolutionOptions = arrayOf(
        "BEST / MAXIMUM (AVAILABLE)",
        "HIGH (720P)",
        "MEDIUM (480P)",
        "DATA SAVER (360P)"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val audioOnly = pendingAudioOnly
        pendingAudioOnly = null
        downloadButton.isEnabled = true
        downloadAudioButton.isEnabled = true
        if (isGranted && audioOnly != null) {
            processDownload(audioOnly)
        } else if (!isGranted) {
            log("> [!] Download dibatalkan: izin penyimpanan diperlukan pada Android 9 ke bawah.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEditText = findViewById(R.id.urlEditText)
        pasteButton = findViewById(R.id.pasteButton)
        clearButton = findViewById(R.id.clearButton)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        downloadButton = findViewById(R.id.downloadButton)
        downloadAudioButton = findViewById(R.id.downloadAudioButton)
        progressBar = findViewById(R.id.progressBar)
        percentTextView = findViewById(R.id.percentTextView)
        statusTextView = findViewById(R.id.statusTextView)
        previewContainer = findViewById(R.id.previewContainer)
        extractorWebView = findViewById(R.id.extractorWebView)
        mainScrollView = findViewById(R.id.mainScrollView)
        impactBanner = findViewById(R.id.impactBanner)
        bannerTitleTextView = findViewById(R.id.bannerTitleTextView)
        bannerDescTextView = findViewById(R.id.bannerDescTextView)
        playMediaButton = findViewById(R.id.playMediaButton)
        closeBannerButton = findViewById(R.id.closeBannerButton)

        closeBannerButton.setOnClickListener { dismissBanner() }

        val spinnerAdapter = ArrayAdapter(
            this,
            R.layout.brutal_spinner_item,
            platformOptions
        ).apply {
            setDropDownViewResource(R.layout.brutal_spinner_dropdown_item)
        }
        sourceSpinner.adapter = spinnerAdapter

        val resolutionAdapter = ArrayAdapter(
            this,
            R.layout.brutal_spinner_item,
            resolutionOptions
        ).apply {
            setDropDownViewResource(R.layout.brutal_spinner_dropdown_item)
        }
        resolutionSpinner.adapter = resolutionAdapter

        setupWebView()

        applySquashAndStretch(pasteButton) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val rawText = clip.getItemAt(0).text?.toString() ?: ""
                val cleanUrl = extractCleanUrl(rawText)
                if (cleanUrl.isNotEmpty()) {
                    urlEditText.setText(cleanUrl)
                    urlEditText.setSelection(cleanUrl.length)
                    log("> Pasted URL:\n$cleanUrl")
                    autoSelectPlatform(cleanUrl)
                } else {
                    Toast.makeText(this, "Teks bukan tautan URL!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
            }
        }

        applySquashAndStretch(clearButton) {
            urlEditText.text?.clear()
            previewContainer.visibility = View.GONE
            log("> Input dibersihkan. Siap menerima link.")
        }

        applySquashAndStretch(downloadButton) {
            processDownload(isAudioOnly = false)
        }

        applySquashAndStretch(downloadAudioButton) {
            processDownload(isAudioOnly = true)
        }

        applySquashAndStretch(playMediaButton) {
            lastSavedMedia?.let { openMedia(it) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        extractorWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun extractCleanUrl(input: String): String {
        val pattern = Pattern.compile("https?://[a-zA-Z0-9._/\\-+?=%&@#]+")
        val matcher = pattern.matcher(input)
        return if (matcher.find()) matcher.group(0) ?: "" else input.trim()
    }

    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P || ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        downloadScope.cancel()
        client.dispatcher.cancelAll()
        extractorWebView.destroy()
        super.onDestroy()
    }

    private fun autoSelectPlatform(url: String) {
        val lower = url.lowercase()
        when {
            lower.contains("tiktok.com") || lower.contains("douyin.com") -> {
                sourceSpinner.setSelection(1) // TIKTOK
            }
            lower.contains("instagram.com") || lower.contains("instagr.am") -> {
                sourceSpinner.setSelection(2) // INSTAGRAM
            }
            lower.contains("youtube.com") || lower.contains("youtu.be") -> {
                sourceSpinner.setSelection(3) // YOUTUBE
            }
            lower.contains("twitter.com") || lower.contains("x.com") -> {
                sourceSpinner.setSelection(4) // TWITTER / X
            }
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> {
                sourceSpinner.setSelection(5) // FACEBOOK
            }
            lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".jpg") -> {
                sourceSpinner.setSelection(6) // DIRECT LINK
            }
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            statusTextView.text = message
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            val current = statusTextView.text.toString()
            statusTextView.text = "$current\n$line"
        }
    }

    private fun processDownload(isAudioOnly: Boolean) {
        val rawInput = urlEditText.text.toString().trim()
        val rawUrl = extractCleanUrl(rawInput)

        if (rawUrl.isEmpty()) {
            Toast.makeText(this, "Masukkan URL terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasStoragePermission()) {
            if (pendingAudioOnly == null) {
                pendingAudioOnly = isAudioOnly
                downloadButton.isEnabled = false
                downloadAudioButton.isEnabled = false
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }

        val selectedPlatform = sourceSpinner.selectedItem.toString()
        val detectedPlatform = if (selectedPlatform == "AUTO-DETECT") {
            when {
                rawUrl.contains("tiktok.com") || rawUrl.contains("douyin.com") -> "TIKTOK"
                rawUrl.contains("instagram.com") || rawUrl.contains("instagr.am") -> "INSTAGRAM"
                rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be") -> "YOUTUBE"
                rawUrl.contains("twitter.com") || rawUrl.contains("x.com") -> "TWITTER / X"
                rawUrl.contains("facebook.com") || rawUrl.contains("fb.watch") || rawUrl.contains("fb.com") -> "FACEBOOK"
                else -> "DIRECT LINK"
            }
        } else {
            selectedPlatform
        }

        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        percentTextView.visibility = View.VISIBLE
        percentTextView.text = "RESOLVING..."
        downloadButton.isEnabled = false
        downloadAudioButton.isEnabled = false

        val selectedResolution = resolutionSpinner.selectedItem.toString()
        log("> [1/3] Memproses: $detectedPlatform [$selectedResolution]\n> URL: $rawUrl")

        downloadScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    when (detectedPlatform) {
                        "TIKTOK" -> listOf(resolveTikTok(rawUrl, isAudioOnly, selectedResolution))
                        "INSTAGRAM" -> resolveInstagram(rawUrl, isAudioOnly)
                        "YOUTUBE" -> listOf(resolveYouTube(rawUrl, isAudioOnly, selectedResolution))
                        "TWITTER / X" -> resolveTwitter(rawUrl, isAudioOnly, selectedResolution)
                        "FACEBOOK" -> resolveFacebook(rawUrl, isAudioOnly, selectedResolution)
                        else -> listOf(MediaResult(rawUrl, "DirectMedia", "Kualitas asli tautan; tidak dikonversi."))
                    }
                }
                val selected = if (resolved.size > 1) chooseMediaItems(resolved, detectedPlatform) else resolved
                if (selected == null) {
                    percentTextView.visibility = View.GONE
                    log("> Download dibatalkan. Tidak ada file yang diunduh.")
                    return@launch
                }
                val saved = mutableListOf<SavedMedia>()
                val failures = mutableListOf<String>()
                selected.forEachIndexed { index, media ->
                    log("> [2/3] Item ${index + 1}/${selected.size}\n> ${media.qualityDescription}\n> [3/3] Mengunduh dan memvalidasi format asli...")
                    try {
                        saved.add(withContext(Dispatchers.IO) {
                            if (media.downloadUrl.isBlank()) throw IOException("Tautan media tidak tersedia.")
                            downloadStream(media, isAudioOnly, index + 1, selected.size)
                        })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures.add("Item ${media.itemNumber ?: index + 1}: ${e.message ?: "Unknown error"}")
                    }
                }
                percentTextView.text = "${saved.size}/${selected.size} FILE"
                val heading = when {
                    failures.isEmpty() -> "> SUCCESS!"
                    saved.isEmpty() -> "> [!] DOWNLOAD GAGAL"
                    else -> "> [!] SELESAI SEBAGIAN"
                }
                val details = saved.joinToString("\n") { "> ${it.format.uppercase()}: ${it.details}\n  ${it.location}" }
                val errors = failures.joinToString("\n") { "> [!] $it" }
                log(listOf(heading, "> Tersimpan ${saved.size}/${selected.size} file di Downloads/BrutalDownloader.", details, errors)
                    .filter { it.isNotBlank() }.joinToString("\n"))
                if (saved.isNotEmpty()) {
                    showImpactBanner(saved.last())
                } else {
                    Toast.makeText(this@MainActivity, "Tersimpan ${saved.size}/${selected.size} file", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                percentTextView.visibility = View.GONE
                log("> [!] ERROR: ${e.message ?: "Unknown error"}")
                Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                downloadButton.isEnabled = true
                downloadAudioButton.isEnabled = true
            }
        }
    }

    private data class MediaResult(
        val downloadUrl: String,
        val fileStem: String,
        val qualityDescription: String,
        val kind: InstagramMediaKind? = null,
        val itemNumber: Int? = null,
        val audioDownloadUrl: String? = null
    )

    private data class SavedMedia(
        val location: String,
        val format: String,
        val details: String,
        val uri: Uri? = null
    )

    private suspend fun chooseMediaItems(items: List<MediaResult>, platformName: String): List<MediaResult>? {
        if (items.isEmpty()) throw IOException("Tidak ada foto/video $platformName yang dapat diunduh.")
        if (items.size == 1) return items
        progressBar.visibility = View.GONE
        percentTextView.text = "${items.size} ITEM DITEMUKAN"
        return suspendCancellableCoroutine { continuation ->
            val labels = arrayOf("UNDUH SEMUA (${items.size} ITEM)") + items.map {
                "ITEM ${it.itemNumber}: ${if (it.kind == InstagramMediaKind.PHOTO) "FOTO" else "VIDEO"}"
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("PILIH MEDIA $platformName")
                .setItems(labels) { _, position ->
                    if (continuation.isActive) {
                        continuation.resume(if (position == 0) items else listOf(items[position - 1]))
                    }
                }
                .setNegativeButton("BATAL") { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
            dialog.show()
        }
    }

    private suspend fun resolveTwitter(url: String, isAudioOnly: Boolean, resolution: String): List<MediaResult> {
        val cleanUrl = extractCleanUrl(url)
        val tweetId = Regex("""status(?:es)?/(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: throw IOException("Tautan Twitter / X tidak valid. Pastikan berisi tautan postingan/status.")

        val endpoints = listOf(
            "https://api.vxtwitter.com/Twitter/status/$tweetId",
            "https://api.fxtwitter.com/2/status/$tweetId"
        )
        var lastException: Exception? = null

        for (apiUrl in endpoints) {
            currentCoroutineContext().ensureActive()
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                return withResponse(request) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} dari $apiUrl")
                    val body = response.body?.string() ?: throw IOException("Respons kosong dari $apiUrl")
                    val json = JSONObject(body)

                    // 1. Check vxtwitter media_extended
                    val mediaExtended = json.optJSONArray("media_extended")
                    if (mediaExtended != null && mediaExtended.length() > 0) {
                        val results = mutableListOf<MediaResult>()
                        for (i in 0 until mediaExtended.length()) {
                            val item = mediaExtended.getJSONObject(i)
                            val itemUrl = item.optString("url")
                            if (itemUrl.isEmpty()) continue
                            val itemType = item.optString("type").lowercase()
                            val sizeObj = item.optJSONObject("size")
                            val w = sizeObj?.optInt("width", 0) ?: 0
                            val h = sizeObj?.optInt("height", 0) ?: 0
                            val isVideo = itemType == "video" || itemType == "gif"
                            val kind = if (isVideo) InstagramMediaKind.VIDEO else InstagramMediaKind.PHOTO
                            val desc = if (isVideo) {
                                if (w > 0 && h > 0) "Video Twitter/X [${w}x${h}]" else "Video Twitter/X [HD]"
                            } else {
                                "Foto Twitter/X [Original Quality]"
                            }
                            val prefix = if (isVideo) "Twitter_${tweetId}" else "Twitter_${tweetId}_photo"
                            results.add(
                                MediaResult(
                                    itemUrl,
                                    "${prefix}_${(i + 1).toString().padStart(2, '0')}",
                                    desc,
                                    kind,
                                    i + 1
                                )
                            )
                        }
                        if (results.isNotEmpty()) return@withResponse results
                    }

                    // 2. Check fxtwitter status.media or tweet.media
                    val status = json.optJSONObject("status") ?: json.optJSONObject("tweet") ?: json
                    val media = status.optJSONObject("media")
                        ?: throw IOException("Postingan Twitter/X ini tidak memiliki media video atau foto.")

                    val videos = media.optJSONArray("videos")
                    if (videos != null && videos.length() > 0) {
                        val results = mutableListOf<MediaResult>()
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            val formats = v.optJSONArray("formats") ?: v.optJSONArray("variants")
                            var chosenUrl = v.optString("url")
                            var chosenDesc = "Video Twitter/X [Original Quality]"

                            if (formats != null && formats.length() > 0) {
                                val mp4List = (0 until formats.length()).map { formats.getJSONObject(it) }
                                    .filter {
                                        val c = it.optString("container").lowercase()
                                        val ct = it.optString("content_type").lowercase()
                                        val u = it.optString("url").lowercase()
                                        c == "mp4" || ct == "video/mp4" || u.contains(".mp4")
                                    }
                                if (mp4List.isNotEmpty()) {
                                    val sorted = mp4List.sortedByDescending { it.optLong("bitrate", 0) }
                                    val selected = when {
                                        resolution.contains("BEST") -> sorted.first()
                                        resolution.contains("480") || resolution.contains("360") -> sorted.last()
                                        resolution.contains("720") -> sorted.firstOrNull { it.optInt("height", 0) <= 720 } ?: sorted.first()
                                        else -> sorted.first()
                                    }
                                    chosenUrl = selected.optString("url", chosenUrl)
                                    val bitrate = selected.optLong("bitrate", 0)
                                    val width = selected.optInt("width", 0)
                                    val height = selected.optInt("height", 0)
                                    chosenDesc = when {
                                        width > 0 && height > 0 -> "Video Twitter/X [${width}x${height}]"
                                        bitrate > 0 -> "Video Twitter/X [${bitrate / 1000} kbps]"
                                        else -> "Video Twitter/X"
                                    }
                                }
                            }
                            results.add(
                                MediaResult(
                                    chosenUrl,
                                    "Twitter_${tweetId}_${(i + 1).toString().padStart(2, '0')}",
                                    chosenDesc,
                                    InstagramMediaKind.VIDEO,
                                    i + 1
                                )
                            )
                        }
                        if (results.isNotEmpty()) return@withResponse results
                    }

                    // Photos in tweet
                    val photos = media.optJSONArray("photos")
                    if (photos != null && photos.length() > 0) {
                        val results = mutableListOf<MediaResult>()
                        for (i in 0 until photos.length()) {
                            val p = photos.getJSONObject(i)
                            val photoUrl = p.optString("url")
                            results.add(
                                MediaResult(
                                    photoUrl,
                                    "Twitter_${tweetId}_photo_${(i + 1).toString().padStart(2, '0')}",
                                    "Foto Twitter/X [Original Quality]",
                                    InstagramMediaKind.PHOTO,
                                    i + 1
                                )
                            )
                        }
                        return@withResponse results
                    }

                    throw IOException("Tidak ditemukan video atau foto pada postingan Twitter/X ini.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
            }
        }

        // Fallback: TwitSave
        return try {
            resolveTwitterTwitSaveFallback(cleanUrl, tweetId)
        } catch (e: Exception) {
            throw IOException("Gagal mengekstrak Twitter/X: ${lastException?.message ?: e.message}", lastException ?: e)
        }
    }

    private suspend fun resolveTwitterTwitSaveFallback(url: String, tweetId: String): List<MediaResult> {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://twitsave.com/info?url=$encodedUrl")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return withResponse(request) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} dari TwitSave")
            val html = response.body?.string().orEmpty()
            val links = Regex("""href=["'](https://video\.twimg\.com/[^"']+)["']""").findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (links.isEmpty()) throw IOException("TwitSave tidak menemukan tautan video.")
            val chosen = links.first()
            listOf(
                MediaResult(
                    chosen,
                    "Twitter_${tweetId}_01",
                    "Video Twitter/X dari TwitSave; kualitas asli.",
                    InstagramMediaKind.VIDEO,
                    1
                )
            )
        }
    }

    private suspend fun resolveFacebook(url: String, isAudioOnly: Boolean, resolution: String): List<MediaResult> {
        val cleanUrl = extractCleanUrl(url)
        val shortcode = when {
            cleanUrl.contains("/watch") -> cleanUrl.substringAfter("v=").substringBefore("&").substringBefore("?").trim()
            cleanUrl.contains("/reel/") -> cleanUrl.substringAfter("/reel/").substringBefore("/").substringBefore("?").trim()
            cleanUrl.contains("/videos/") -> cleanUrl.substringAfter("/videos/").substringBefore("/").substringBefore("?").trim()
            else -> "fb_" + System.currentTimeMillis()
        }

        val requestBody = FormBody.Builder()
            .add("url", cleanUrl)
            .build()

        val request = Request.Builder()
            .url("https://snapsave.app/action.php")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Referer", "https://snapsave.app/")
            .header("Origin", "https://snapsave.app")
            .post(requestBody)
            .build()

        var usedFallback = false
        val items = withResponse(request) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} dari resolver Facebook")
            val body = response.body?.string() ?: throw IOException("Respons kosong dari resolver Facebook")
            try {
                InstagramMediaParser.parseResponse(body)
            } catch (error: IOException) {
                null
            }
        } ?: run {
            usedFallback = true
            withContext(Dispatchers.Main) {
                log("> [!] SnapSave gagal; mencoba resolver cadangan Facebook.")
            }
            resolveFacebookFallback(cleanUrl)
        }

        val selectedItems = if (items.size > 1 && !resolution.contains("BEST") && resolution.contains("360")) {
            listOf(items.last())
        } else {
            items
        }

        return selectedItems.mapIndexed { index, item ->
            val kindLabel = if (item.kind == InstagramMediaKind.PHOTO) "Foto" else "Video"
            val description = if (usedFallback) {
                "$kindLabel Facebook dari resolver cadangan; kualitas asli."
            } else {
                "$kindLabel Facebook; kualitas asli tanpa re-encode."
            }
            MediaResult(
                item.url,
                "Facebook_${shortcode}_${(index + 1).toString().padStart(2, '0')}",
                description,
                item.kind,
                index + 1
            )
        }
    }

    private suspend fun resolveFacebookFallback(url: String): List<InstagramMediaItem> {
        val form = FormBody.Builder().add("URLz", url).build()
        val request = Request.Builder()
            .url("https://fdown.net/download.php")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "https://fdown.net/")
            .post(form)
            .build()
        return withResponse(request) { response ->
            if (response.isSuccessful) {
                val html = response.body?.string().orEmpty()
                val hd = Regex("""id=["']hdlink["']\s+href=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val sd = Regex("""id=["']sdlink["']\s+href=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val link = hd ?: sd
                if (!link.isNullOrBlank()) {
                    return@withResponse listOf(InstagramMediaItem(link.replace("&amp;", "&"), InstagramMediaKind.VIDEO))
                }
            }
            resolveInstagramFallback(url)
        }
    }

    private suspend fun resolveTikTok(url: String, isAudio: Boolean, resolution: String): MediaResult {
        val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8") + "&hd=1"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        withResponse(request) { response ->
            if (!response.isSuccessful) throw Exception("TikWM HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response from TikWM")
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code != 0) {
                val msg = json.optString("msg", "Gagal memproses TikTok")
                throw Exception(msg)
            }
            val data = json.getJSONObject("data")
            val title = data.optString("title", "tiktok_video")
                .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                .take(30)

            if (isAudio) {
                val musicUrl = data.optString("music")
                return MediaResult(musicUrl, "TikTok_Audio_$title", "Audio asli dari sumber; format diperiksa saat download.")
            }

            val hdPlay = data.optString("hdplay")
            val play = data.optString("play")

            val useHd = resolution.contains("BEST", ignoreCase = true) && hdPlay.isNotBlank()
            val videoUrl = if (useHd) hdPlay else play.ifBlank { hdPlay }
            val description = if (useHd) {
                "Varian HD dari TikWM; resolusi aktual diperiksa setelah download."
            } else {
                "Kualitas asli TikWM; pilihan resolusi spesifik tidak tersedia dan tidak dikonversi."
            }
            return MediaResult(videoUrl, "TikTok_$title", description)
        }
    }


    private suspend fun resolveInstagram(url: String, isAudioOnly: Boolean): List<MediaResult> {
        if (isAudioOnly) {
            throw IOException("Resolver Instagram belum menyediakan audio terpisah. Unduh video; konversi MP3 belum didukung.")
        }
        val cleanUrl = extractCleanUrl(url).substringBefore('?').substringBefore('#')
        val shortcode = when {
            cleanUrl.contains("/reel/") -> cleanUrl.substringAfter("/reel/").substringBefore("/").substringBefore("?").trim()
            cleanUrl.contains("/p/") -> cleanUrl.substringAfter("/p/").substringBefore("/").substringBefore("?").trim()
            else -> "media_" + System.currentTimeMillis()
        }

        val requestBody = FormBody.Builder()
            .add("url", cleanUrl)
            .build()

        val request = Request.Builder()
            .url("https://snapsave.app/action.php")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Referer", "https://snapsave.app/")
            .header("Origin", "https://snapsave.app")
            .post(requestBody)
            .build()

        var usedFallback = false
        val items = withResponse(request) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} dari resolver Instagram")
            val body = response.body?.string() ?: throw IOException("Respons kosong dari resolver Instagram")
            try {
                InstagramMediaParser.parseResponse(body)
            } catch (error: IOException) {
                // A successful resolver response can contain a download button without a media URL.
                null
            }
        } ?: run {
            usedFallback = true
            withContext(Dispatchers.Main) {
                log("> [!] SnapSave tidak menyediakan tautan valid; mencoba resolver cadangan curl-x.com.")
            }
            resolveInstagramFallback(cleanUrl)
        }
        return items.mapIndexed { index, item ->
            val kindLabel = if (item.kind == InstagramMediaKind.PHOTO) "Foto" else "Video"
            val description = if (usedFallback) {
                "$kindLabel Instagram dari resolver cadangan; kualitas sesuai sumber, tanpa konversi."
            } else {
                "$kindLabel asli Instagram; tanpa konversi atau perubahan resolusi."
            }
            MediaResult(
                item.url,
                "Instagram_${shortcode}_${(index + 1).toString().padStart(2, '0')}",
                description,
                item.kind,
                index + 1
            )
        }
    }

    private suspend fun resolveInstagramFallback(url: String): List<InstagramMediaItem> {
        val request = Request.Builder()
            .url("https://www.curl-x.com/api/extract")
            .header("User-Agent", "Mozilla/5.0")
            .post(JSONObject().put("url", url).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withResponse(request) { response ->
            if (!response.isSuccessful) {
                val reason = if (response.code == 429) "Batas permintaan tercapai; tunggu lalu coba lagi." else
                    "HTTP ${response.code}; coba lagi nanti."
                throw IOException("SnapSave tidak menyediakan tautan video. Resolver cadangan gagal: $reason")
            }
            val body = response.body?.string()
                ?: throw IOException("Respons resolver Instagram cadangan kosong; coba lagi nanti.")
            InstagramFallbackParser.parseResponse(body, url)
        }
    }

    @Synchronized
    private fun ensureNewPipeInitialized() {
        if (!newPipeInitialized) {
            NewPipe.init(NewPipeDownloader(client))
            newPipeInitialized = true
        }
    }

    private suspend fun resolveYouTube(url: String, isAudio: Boolean, resolution: String): MediaResult {
        val videoId = YouTubeStreamParser.extractVideoId(url)
            ?: throw IOException("Tautan YouTube tidak valid")
        val targetHeight = when {
            resolution.contains("BEST") -> null
            resolution.contains("720") -> 720
            resolution.contains("480") -> 480
            else -> 360
        }
        val errors = mutableListOf<String>()

        // 1. Primary: Direct on-device extraction via NewPipeExtractor
        try {
            currentCoroutineContext().ensureActive()
            ensureNewPipeInitialized()
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
            extractor.fetchPage()

            val muxedVideos = extractor.videoStreams.mapNotNull { stream ->
                if (!stream.isUrl || stream.content.isBlank()) return@mapNotNull null
                val fmt = stream.format ?: return@mapNotNull null
                if (fmt != NewPipeMediaFormat.MPEG_4) return@mapNotNull null
                val height = Regex("(\\d+)").find(stream.getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (height > 0) YouTubeCandidate(height, stream.content, stream.bitrate.toLong(), isH264 = true) else null
            }

            val adaptiveVideos = extractor.videoOnlyStreams.mapNotNull { stream ->
                if (!stream.isUrl || stream.content.isBlank()) return@mapNotNull null
                val fmt = stream.format ?: return@mapNotNull null
                if (fmt != NewPipeMediaFormat.MPEG_4) return@mapNotNull null
                val codecStr = stream.codec.orEmpty().lowercase()
                if (codecStr.contains("av01")) return@mapNotNull null
                val height = Regex("(\\d+)").find(stream.getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val isH264 = codecStr.contains("avc1") || (!codecStr.contains("vp9") && !codecStr.contains("vp09"))
                if (height > 0) YouTubeCandidate(height, stream.content, stream.bitrate.toLong(), isH264 = isH264) else null
            }

            val audioStreams = extractor.audioStreams.mapNotNull { stream ->
                if (!stream.isUrl || stream.content.isBlank()) return@mapNotNull null
                val fmt = stream.format ?: return@mapNotNull null
                val isM4a = fmt == NewPipeMediaFormat.M4A
                if (!isM4a && fmt != NewPipeMediaFormat.WEBMA && fmt != NewPipeMediaFormat.WEBMA_OPUS) {
                    return@mapNotNull null
                }
                YouTubeAudioCandidate(stream.content, stream.averageBitrate.toLong(), isM4a = isM4a)
            }

            val resolved = YouTubeStreamParser.selectStream(
                muxedVideos = muxedVideos,
                adaptiveVideos = adaptiveVideos,
                audioStreams = audioStreams,
                isAudioOnly = isAudio,
                targetHeight = targetHeight
            )
            val stem = if (isAudio) "YouTube_Audio_$videoId" else "YouTube_$videoId"
            return MediaResult(
                downloadUrl = resolved.videoUrl,
                fileStem = stem,
                qualityDescription = resolved.description,
                audioDownloadUrl = resolved.audioUrl
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            errors.add("Direct: ${e.message ?: e::class.java.simpleName}")
        }

        // 2. Fallback: Live Invidious instances (supporting local=true proxy & adaptive muxing)
        val instances = arrayOf(
            "https://yt.chocolatemoo53.com",
            "https://inv.nadeko.net",
            "https://yewtu.be"
        )
        for (host in instances) {
            currentCoroutineContext().ensureActive()
            try {
                val req = Request.Builder()
                    .url("$host/api/v1/videos/$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                return withResponse(req) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("Respons kosong")
                    val resolved = YouTubeStreamParser.parseInvidiousResponse(
                        host = host,
                        videoId = videoId,
                        jsonBody = body,
                        isAudioOnly = isAudio,
                        targetHeight = targetHeight
                    )
                    val stem = if (isAudio) "YouTube_Audio_$videoId" else "YouTube_$videoId"
                    MediaResult(
                        downloadUrl = resolved.videoUrl,
                        fileStem = stem,
                        qualityDescription = resolved.description,
                        audioDownloadUrl = resolved.audioUrl
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val label = host.removePrefix("https://")
                errors.add("$label: ${e.message ?: e::class.java.simpleName}")
            }
        }
        throw IOException("Gagal mengekstrak YouTube (${errors.joinToString("; ")})")
    }

    private suspend inline fun <T> withResponse(request: Request, block: (okhttp3.Response) -> T): T {
        val call = client.newCall(request)
        // execute()/stream reads block IO threads; a sibling job cancels the socket on cancellation.
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            currentCoroutineContext().ensureActive()
            call.execute().use(block)
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private suspend fun downloadStream(
        media: MediaResult,
        audioOnly: Boolean,
        itemPosition: Int,
        itemCount: Int
    ): SavedMedia {
        if (!audioOnly && !media.audioDownloadUrl.isNullOrBlank()) {
            return downloadAndMuxStream(media, media.audioDownloadUrl, itemPosition, itemCount)
        }
        val request = Request.Builder()
            .url(media.downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        withResponse(request) { response ->
            if (response.code != 200) throw IOException("HTTP ${response.code}: stream lengkap tidak tersedia.")
            val body = response.body ?: throw IOException("Respons media kosong.")
            val contentLength = body.contentLength()
            body.byteStream().buffered().use { input ->
                input.mark(4096)
                val header = ByteArray(4096)
                var headerSize = 0
                while (headerSize < header.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(header, headerSize, header.size - headerSize)
                    if (count == -1) break
                    headerSize += count
                }
                input.reset()
                val format = MediaDownloadPolicy.detectFormat(header.copyOf(headerSize), audioOnly)
                if (media.kind == InstagramMediaKind.PHOTO && !format.mimeType.startsWith("image/")) {
                    throw IOException("Item Instagram ditandai sebagai foto tetapi sumber tidak mengirim gambar yang didukung.")
                }
                if (media.kind == InstagramMediaKind.VIDEO && !format.mimeType.startsWith("video/")) {
                    throw IOException("Item Instagram ditandai sebagai video tetapi sumber tidak mengirim video yang didukung.")
                }
                val fileName = "${media.fileStem}.${format.extension}"
                currentCoroutineContext().ensureActive()
                DownloadStorage(this@MainActivity).create(fileName, format.mimeType).use { pending ->
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.isIndeterminate = contentLength <= 0
                        progressBar.max = 100
                        progressBar.progress = 0
                        percentTextView.text = "$itemPosition/$itemCount DOWNLOADING..."
                    }
                    var bytesCopied = 0L
                    var lastUpdate = 0L
                    pending.outputStream.use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            bytesCopied += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 250) {
                                lastUpdate = now
                                withContext(Dispatchers.Main) {
                                    val progress = if (contentLength > 0) {
                                        ((bytesCopied.toDouble() / contentLength) * 100).toInt().coerceIn(0, 100)
                                    } else null
                                    if (progress != null) {
                                        progressBar.progress = progress
                                        percentTextView.animate()
                                            .scaleX(1.18f)
                                            .scaleY(1.18f)
                                            .setDuration(60)
                                            .withEndAction {
                                                percentTextView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                                            }
                                            .start()
                                    }
                                    percentTextView.text = "$itemPosition/$itemCount " + (progress?.let { "$it%" } ?: "${bytesCopied / 1024} KB")
                                    statusTextView.text = "> ITEM $itemPosition/$itemCount: ${bytesCopied / 1024} KB\n> Format: ${format.extension.uppercase()}\n> ${media.qualityDescription}"
                                }
                            }
                        }
                    }
                    MediaDownloadPolicy.requireComplete(contentLength, bytesCopied)
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main) { percentTextView.text = "VALIDATING..." }
                    val actualQuality = if (format.mimeType.startsWith("image/")) {
                        ImageMediaValidator.validate(this@MainActivity, pending.uri, format)
                    } else {
                        validateMedia(pending.uri, format, audioOnly)
                    }
                    currentCoroutineContext().ensureActive()
                    val location = pending.publish()
                    return SavedMedia(location, format.extension, actualQuality, pending.uri)
                }
            }
        }
    }

    private suspend fun downloadAndMuxStream(
        media: MediaResult,
        audioUrl: String,
        itemPosition: Int,
        itemCount: Int
    ): SavedMedia {
        val tempId = java.util.UUID.randomUUID().toString()
        val videoTemp = File(cacheDir, "mux_v_$tempId.mp4")
        val audioTemp = File(cacheDir, "mux_a_$tempId.m4a")
        val muxedTemp = File(cacheDir, "mux_out_$tempId.mp4")
        try {
            downloadToTempFile(
                url = media.downloadUrl,
                dest = videoTemp,
                audioOnly = false,
                stageLabel = "VIDEO",
                progressStart = 0,
                progressSpan = 65,
                media = media,
                itemPosition = itemPosition,
                itemCount = itemCount
            )
            downloadToTempFile(
                url = audioUrl,
                dest = audioTemp,
                audioOnly = true,
                stageLabel = "AUDIO",
                progressStart = 65,
                progressSpan = 25,
                media = media,
                itemPosition = itemPosition,
                itemCount = itemCount
            )

            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) {
                progressBar.isIndeterminate = true
                percentTextView.text = "MUXING MP4..."
                statusTextView.text = "> ITEM $itemPosition/$itemCount: Menggabungkan video & audio...\n> ${media.qualityDescription}"
            }
            muxMp4Tracks(videoTemp, audioTemp, muxedTemp)

            currentCoroutineContext().ensureActive()
            val mp4Format = DownloadFormat("mp4", "video/mp4")
            DownloadStorage(this@MainActivity).create("${media.fileStem}.mp4", mp4Format.mimeType).use { pending ->
                pending.outputStream.use { output ->
                    muxedTemp.inputStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                withContext(Dispatchers.Main) { percentTextView.text = "VALIDATING..." }
                val actualQuality = validateMedia(pending.uri, mp4Format, audioOnly = false)
                currentCoroutineContext().ensureActive()
                val location = pending.publish()
                return SavedMedia(location, mp4Format.extension, actualQuality, pending.uri)
            }
        } finally {
            videoTemp.delete()
            audioTemp.delete()
            muxedTemp.delete()
        }
    }

    private suspend fun downloadToTempFile(
        url: String,
        dest: File,
        audioOnly: Boolean,
        stageLabel: String,
        progressStart: Int,
        progressSpan: Int,
        media: MediaResult,
        itemPosition: Int,
        itemCount: Int
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        withResponse(request) { response ->
            if (response.code != 200 && response.code != 206) {
                throw IOException("HTTP ${response.code}: stream $stageLabel tidak tersedia.")
            }
            val body = response.body ?: throw IOException("Respons $stageLabel kosong.")
            val contentLength = body.contentLength()
            body.byteStream().buffered().use { input ->
                input.mark(4096)
                val header = ByteArray(4096)
                var headerSize = 0
                while (headerSize < header.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(header, headerSize, header.size - headerSize)
                    if (count == -1) break
                    headerSize += count
                }
                input.reset()
                MediaDownloadPolicy.detectFormat(header.copyOf(headerSize), audioOnly)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = contentLength <= 0
                    progressBar.max = 100
                    progressBar.progress = progressStart
                    percentTextView.text = "$itemPosition/$itemCount $stageLabel..."
                }
                var bytesCopied = 0L
                var lastUpdate = 0L
                dest.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        bytesCopied += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            withContext(Dispatchers.Main) {
                                val pct = if (contentLength > 0) {
                                    (progressStart + ((bytesCopied.toDouble() / contentLength) * progressSpan)).toInt().coerceIn(0, 99)
                                } else null
                                if (pct != null) {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = pct
                                }
                                percentTextView.text = pct?.let { "$it%" } ?: "$stageLabel ${bytesCopied / 1024} KB"
                                statusTextView.text = "> ITEM $itemPosition/$itemCount [$stageLabel]: ${bytesCopied / 1024} KB\n> ${media.qualityDescription}"
                            }
                        }
                    }
                }
                MediaDownloadPolicy.requireComplete(contentLength, bytesCopied)
            }
        }
    }

    private suspend fun muxMp4Tracks(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoSrcIndex = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("Stream video tidak berisi track video yang valid.")

            val audioSrcIndex = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Stream audio tidak berisi track audio yang valid.")

            val videoFormat = videoExtractor.getTrackFormat(videoSrcIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioSrcIndex)
            videoExtractor.selectTrack(videoSrcIndex)
            audioExtractor.selectTrack(audioSrcIndex)

            val activeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val videoDstIndex = activeMuxer.addTrack(videoFormat)
            val audioDstIndex = activeMuxer.addTrack(audioFormat)
            activeMuxer.start()

            copyMuxTrack(videoExtractor, videoFormat, activeMuxer, videoDstIndex)
            copyMuxTrack(audioExtractor, audioFormat, activeMuxer, audioDstIndex)

            activeMuxer.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gagal menggabungkan video & audio YouTube: ${e.message}", e)
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }

    private suspend fun copyMuxTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        muxer: MediaMuxer,
        dstTrackIndex: Int
    ) {
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 0
        val bufferSize = maxOf(maxInput, 512 * 1024).coerceIn(256 * 1024, 16 * 1024 * 1024)
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var lastPts = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            bufferInfo.offset = 0
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val pts = extractor.sampleTime.coerceAtLeast(0L)
            bufferInfo.presentationTimeUs = if (pts > lastPts) pts else lastPts + 1L
            lastPts = bufferInfo.presentationTimeUs
            bufferInfo.size = sampleSize
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }


    private fun validateMedia(uri: Uri, format: DownloadFormat, audioOnly: Boolean): String {
        val extractor = MediaExtractor()
        try {
            contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                if (descriptor == null) throw IOException("File download tidak dapat diperiksa.")
                extractor.setDataSource(descriptor.fileDescriptor)
                var hasAudio = false
                var hasVideo = false
                var dimensions: String? = null
                val audioMimes = mutableSetOf<String>()
                for (index in 0 until extractor.trackCount) {
                    val track = extractor.getTrackFormat(index)
                    val mime = track.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        hasAudio = true
                        audioMimes.add(mime)
                    }
                    if (mime.startsWith("video/")) {
                        hasVideo = true
                        if (track.containsKey(MediaFormat.KEY_WIDTH) && track.containsKey(MediaFormat.KEY_HEIGHT)) {
                            dimensions = "${track.getInteger(MediaFormat.KEY_WIDTH)} × ${track.getInteger(MediaFormat.KEY_HEIGHT)}"
                        }
                    }
                    if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                        extractor.selectTrack(index)
                        try {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val maxInput = if (track.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                                track.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            } else 0
                            val sampleSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                extractor.sampleSize.toInt()
                            } else -1
                            val bufferCapacity = maxOf(maxInput, sampleSize, 64 * 1024).coerceIn(64 * 1024, 32 * 1024 * 1024)
                            val buffer = ByteBuffer.allocate(bufferCapacity)
                            val read = extractor.readSampleData(buffer, 0)
                            if (read <= 0 && extractor.sampleTrackIndex < 0) {
                                throw IOException("Sampel media kosong, rusak, atau trek tidak valid.")
                            }
                        } finally {
                            extractor.unselectTrack(index)
                        }
                    }
                }
                if (!hasAudio && !hasVideo) throw IOException("Respons tidak berisi track audio/video yang valid.")
                if (format.extension == "mp3" && audioMimes != setOf("audio/mpeg")) {
                    throw IOException("Isi audio bukan MP3 meskipun memiliki metadata ID3; file tidak disimpan dengan ekstensi yang salah.")
                }
                if (format.mimeType.startsWith("audio/") && hasVideo) {
                    throw IOException("Container audio ternyata berisi video; respons sumber tidak sesuai.")
                }
                if (audioOnly && (!hasAudio || hasVideo)) {
                    throw IOException("Sumber tidak menyediakan audio terpisah. Video tidak akan diganti ekstensi menjadi audio.")
                }
                if (!audioOnly && format.mimeType.startsWith("video/") && !hasVideo) {
                    throw IOException("Sumber hanya berisi audio. Gunakan tombol audio agar format disimpan dengan benar.")
                }
                return if (hasVideo) {
                    "Resolusi aktual: ${dimensions ?: "tidak tersedia"}"
                } else {
                    "Audio asli terverifikasi; tanpa konversi MP3."
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Format media tidak valid atau tidak didukung perangkat ini.", e)
        } finally {
            extractor.release()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applySquashAndStretch(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.animate()
                        .scaleX(0.93f)
                        .scaleY(0.93f)
                        .translationY(6f)
                        .setDuration(70)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .setInterpolator(OvershootInterpolator(3.0f))
                        .setDuration(200)
                        .withEndAction {
                            if (v.isEnabled) onClick()
                        }
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .setDuration(100)
                        .start()
                }
            }
            true
        }
    }

    private fun showImpactBanner(item: SavedMedia) {
        lastSavedMedia = item
        runOnUiThread {
            bannerTitleTextView.text = "FILE BERHASIL DISIMPAN!"
            bannerDescTextView.text = item.location
            playMediaButton.text = if (item.format.lowercase() in listOf("mp3", "m4a", "aac")) {
                "▶ PUTAR AUDIO SEKARANG"
            } else {
                "▶ PUTAR VIDEO SEKARANG"
            }

            impactBanner.visibility = View.VISIBLE
            impactBanner.translationY = -600f
            impactBanner.alpha = 0f

            impactBanner.animate()
                .translationY(0f)
                .alpha(1f)
                .setInterpolator(OvershootInterpolator(2.2f))
                .setDuration(450)
                .withEndAction {
                    triggerScreenShake()
                    triggerHapticImpact()
                }
                .start()

            impactBanner.removeCallbacks(dismissBannerRunnable)
            impactBanner.postDelayed(dismissBannerRunnable, 7000)
        }
    }

    private fun dismissBanner() {
        runOnUiThread {
            impactBanner.animate()
                .translationY(-600f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction { impactBanner.visibility = View.GONE }
                .start()
        }
    }

    private fun triggerScreenShake() {
        val shake = 14f
        mainScrollView.animate()
            .translationY(shake)
            .setDuration(45)
            .withEndAction {
                mainScrollView.animate()
                    .translationY(-shake * 0.6f)
                    .setDuration(40)
                    .withEndAction {
                        mainScrollView.animate()
                            .translationY(0f)
                            .setDuration(35)
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun triggerHapticImpact() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    private fun openMedia(item: SavedMedia) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val ext = item.format.lowercase()
            val mimeType = when (ext) {
                "mp4", "mkv", "webm", "mov" -> "video/*"
                "mp3", "m4a", "aac", "wav", "flac" -> "audio/*"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
                else -> "*/*"
            }
            val uri = item.uri ?: run {
                val file = File(item.location)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
                } else {
                    Uri.parse(item.location)
                }
            }
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(Intent.createChooser(intent, "Buka Media"))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka media: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}


