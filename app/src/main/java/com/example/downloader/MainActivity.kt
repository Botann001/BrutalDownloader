package com.example.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle

import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingAudioOnly: Boolean? = null

    private val platformOptions = arrayOf(
        "AUTO-DETECT",
        "TIKTOK",
        "INSTAGRAM",
        "YOUTUBE",
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

        pasteButton.setOnClickListener {
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

        clearButton.setOnClickListener {
            urlEditText.text?.clear()
            previewContainer.visibility = View.GONE
            log("> Input dibersihkan. Siap menerima link.")
        }

        downloadButton.setOnClickListener {
            processDownload(isAudioOnly = false)
        }

        downloadAudioButton.setOnClickListener {
            processDownload(isAudioOnly = true)
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
            lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".jpg") -> {
                sourceSpinner.setSelection(4) // DIRECT LINK
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
                        else -> listOf(MediaResult(rawUrl, "DirectMedia", "Kualitas asli tautan; tidak dikonversi."))
                    }
                }
                val selected = if (detectedPlatform == "INSTAGRAM") chooseInstagramItems(resolved) else resolved
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
                Toast.makeText(this@MainActivity, "Tersimpan ${saved.size}/${selected.size} file", Toast.LENGTH_LONG).show()
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
        val itemNumber: Int? = null
    )

    private data class SavedMedia(val location: String, val format: String, val details: String)

    private suspend fun chooseInstagramItems(items: List<MediaResult>): List<MediaResult>? {
        if (items.isEmpty()) throw IOException("Tidak ada foto/video Instagram yang dapat diunduh.")
        if (items.size == 1) return items
        progressBar.visibility = View.GONE
        percentTextView.text = "${items.size} ITEM DITEMUKAN"
        return suspendCancellableCoroutine { continuation ->
            val labels = arrayOf("UNDUH SEMUA (${items.size} ITEM)") + items.map {
                "ITEM ${it.itemNumber}: ${if (it.kind == InstagramMediaKind.PHOTO) "FOTO" else "VIDEO"}"
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("PILIH MEDIA INSTAGRAM")
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

    private suspend fun resolveYouTube(url: String, isAudio: Boolean, resolution: String): MediaResult {
        val videoId = when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/")
            url.contains("youtube.com/shorts/") -> url.substringAfter("shorts/").substringBefore("?").substringBefore("/")
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            else -> throw IOException("Tautan YouTube tidak valid")
        }
        val instances = arrayOf(
            "https://yt.chocolatemoo53.com",
            "https://invidious.nerdvpn.de",
            "https://inv.tux.pizza",
            "https://invidious.jing.rocks"
        )
        var lastFailure: Exception? = null
        for (host in instances) {
            currentCoroutineContext().ensureActive()
            try {
                val req = Request.Builder().url("$host/api/v1/videos/$videoId").build()
                withResponse(req) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} dari $host")
                    val json = JSONObject(response.body?.string() ?: throw IOException("Respons kosong"))
                    val formats = json.optJSONArray(if (isAudio) "adaptiveFormats" else "formatStreams")
                        ?: throw IOException("Daftar stream tidak tersedia dari $host")
                    val candidates = (0 until formats.length()).map { formats.getJSONObject(it) }
                        .filter { it.optString("url").isNotBlank() }
                    if (isAudio) {
                        val audio = candidates.filter {
                            it.optString("type").startsWith("audio/mp4") ||
                                it.optString("type").startsWith("audio/webm") ||
                                it.optString("container") == "m4a"
                        }.maxByOrNull { it.optLong("bitrate", 0) }
                            ?: throw IOException("Audio terpisah tidak tersedia dari $host; video tidak akan disimpan sebagai MP3.")
                        return MediaResult(audio.getString("url"), "YouTube_Audio_$videoId", "Audio asli; bukan konversi MP3.")
                    }
                    val videos = candidates.mapNotNull { item ->
                        val type = item.optString("type")
                        val container = item.optString("container")
                        val height = Regex("(\\d+)p").find(item.optString("qualityLabel"))
                            ?.groupValues?.get(1)?.toIntOrNull() ?: item.optInt("height", 0)
                        if (height > 0 && (type.startsWith("video/mp4") || container == "mp4")) {
                            height to item.getString("url")
                        } else null
                    }
                    val targetHeight = when {
                        resolution.contains("BEST") -> null
                        resolution.contains("720") -> 720
                        resolution.contains("480") -> 480
                        else -> 360
                    }
                    val selectedHeight = MediaDownloadPolicy.selectVideoHeight(videos.map { it.first }, targetHeight)
                    val selectedUrl = videos.first { it.first == selectedHeight }.second
                    val description = if (targetHeight != null && selectedHeight != targetHeight) {
                        "${targetHeight}p tidak tersedia; memilih ${selectedHeight}p menurut metadata sumber."
                    } else {
                        "Memilih ${selectedHeight}p menurut metadata sumber; resolusi aktual akan diverifikasi."
                    }
                    return MediaResult(selectedUrl, "YouTube_$videoId", description)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                lastFailure = e
            }
        }
        throw IOException("Gagal mengekstrak YouTube: ${lastFailure?.message ?: "stream tidak tersedia"}", lastFailure)
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
                                    if (progress != null) progressBar.progress = progress
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
                    return SavedMedia(location, format.extension, actualQuality)
                }
            }
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
}


