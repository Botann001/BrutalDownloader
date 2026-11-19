package com.example.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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

    private var isResolvingMedia = false

    private val platformOptions = arrayOf(
        "AUTO-DETECT",
        "TIKTOK",
        "INSTAGRAM",
        "YOUTUBE",
        "DIRECT LINK"
    )

    private val resolutionOptions = arrayOf(
        "BEST / MAXIMUM (HD / 1080P)",
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
        if (isGranted) {
            log("> Izin penyimpanan diberikan.")
        } else {
            log("> [!] Izin penyimpanan ditolak.")
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
        checkStoragePermission()

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

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
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

        checkStoragePermission()

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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaInfo = when (detectedPlatform) {
                    "TIKTOK" -> resolveTikTok(rawUrl, isAudioOnly, selectedResolution)
                    "INSTAGRAM" -> resolveInstagram(rawUrl, isAudioOnly, selectedResolution)
                    "YOUTUBE" -> resolveYouTube(rawUrl, isAudioOnly, selectedResolution)
                    else -> MediaResult(
                        downloadUrl = rawUrl,
                        fileName = "DirectMedia_" + System.currentTimeMillis() + if (isAudioOnly) ".mp3" else ".mp4"
                    )
                }

                if (mediaInfo == null || mediaInfo.downloadUrl.isEmpty()) {
                    throw Exception("Gagal mengekstrak tautan media. Pastikan video publik.")
                }

                appendLog("> [2/3] Stream MP4 OK.\n> Filename: ${mediaInfo.fileName}")
                appendLog("> [3/3] Mulai mengunduh...")

                downloadStream(mediaInfo.downloadUrl, mediaInfo.fileName)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    percentTextView.visibility = View.GONE
                    downloadButton.isEnabled = true
                    downloadAudioButton.isEnabled = true
                    log("> [!] ERROR: ${e.message ?: "Unknown error"}")
                    Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class MediaResult(val downloadUrl: String, val fileName: String)

    private fun resolveTikTok(url: String, isAudio: Boolean, resolution: String): MediaResult {
        val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8") + "&hd=1"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
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
                return MediaResult(musicUrl, "TikTok_Audio_${title}.mp3")
            }

            val hdPlay = data.optString("hdplay")
            val play = data.optString("play")

            val isBest = resolution.contains("BEST", ignoreCase = true) || resolution.contains("1080", ignoreCase = true)
            val (videoUrl, qualityTag) = if (isBest && hdPlay.isNotEmpty()) {
                Pair(hdPlay, "1080p_HD")
            } else {
                val tag = if (resolution.contains("360")) "360p" else if (resolution.contains("480")) "480p" else "720p"
                Pair(play, tag)
            }

            return MediaResult(videoUrl, "TikTok_${qualityTag}_${title}.mp4")
        }
    }

    private fun baseConvert(d: String, e: Int, f: Int): String {
        val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val h = charset.substring(0, e)
        val i = charset.substring(0, f)
        var num = 0.0
        val chars = d.reversed()
        for (cIdx in chars.indices) {
            val ch = chars[cIdx]
            val idx = h.indexOf(ch)
            if (idx != -1) {
                num += idx * Math.pow(e.toDouble(), cIdx.toDouble())
            }
        }
        val sb = StringBuilder()
        var nLong = num.toLong()
        while (nLong > 0) {
            val rem = (nLong % f).toInt()
            sb.insert(0, i[rem])
            nLong /= f
        }
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    private fun decodeSnapSave(h: String, u: Int, n: String, t: Int, e: Int, r: Int): String {
        val result = StringBuilder()
        var i = 0
        val len = h.length
        val delimiter = n[e]

        while (i < len) {
            val sBuilder = StringBuilder()
            while (i < len && h[i] != delimiter) {
                sBuilder.append(h[i])
                i++
            }
            var s = sBuilder.toString()
            for (j in 0 until n.length) {
                s = s.replace(n[j].toString(), j.toString())
            }
            if (s.isNotEmpty()) {
                val converted = baseConvert(s, e, 10)
                val codePoint = converted.toLongOrNull() ?: 0L
                val charVal = (codePoint - t).toInt()
                result.append(charVal.toChar())
            }
            i++
        }
        return try {
            URLDecoder.decode(result.toString(), "UTF-8")
        } catch (_: Exception) {
            result.toString()
        }
    }

    private fun resolveInstagram(url: String, isAudioOnly: Boolean, resolution: String): MediaResult {
        val cleanUrl = extractCleanUrl(url)
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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} dari resolver Instagram")
            val body = response.body?.string() ?: throw Exception("Response kosong dari resolver")

            val pattern = Pattern.compile("\\}\\s*\\(\\s*\"(.*?)\"\\s*,\\s*(\\d+)\\s*,\\s*\"(.*?)\"\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*\\)")
            val matcher = pattern.matcher(body)

            if (!matcher.find()) {
                throw Exception("Gagal mengekstrak metadata video Instagram. Pastikan akun dan video publik.")
            }

            val h = matcher.group(1) ?: ""
            val u = matcher.group(2)?.toIntOrNull() ?: 0
            val n = matcher.group(3) ?: ""
            val t = matcher.group(4)?.toIntOrNull() ?: 0
            val e = matcher.group(5)?.toIntOrNull() ?: 0
            val r = matcher.group(6)?.toIntOrNull() ?: 0

            val decodedHtml = decodeSnapSave(h, u, n, t, e, r)

            val urlPattern = Pattern.compile("href=\\\\\"?(https://[^\\\\\"]+?)\\\\\"?[ >]")
            val urlMatcher = urlPattern.matcher(decodedHtml)

            val candidates = mutableListOf<String>()
            while (urlMatcher.find()) {
                val candidate = urlMatcher.group(1) ?: ""
                if (!candidate.contains("google.com") && candidate != "/" && candidate.startsWith("http")) {
                    candidates.add(candidate)
                }
            }

            if (candidates.isEmpty()) {
                throw Exception("Tautan download video MP4 tidak ditemukan. Akun mungkin privat.")
            }

            val chosenUrl = if (resolution.contains("360") && candidates.size > 1) {
                candidates.last()
            } else {
                candidates.first()
            }

            val qualityTag = when {
                resolution.contains("1080") || resolution.contains("BEST") -> "HD"
                resolution.contains("720") -> "720p"
                resolution.contains("480") -> "480p"
                else -> "360p"
            }

            val ext = if (isAudioOnly) ".mp3" else ".mp4"
            val fileName = "Instagram_${qualityTag}_${shortcode}${ext}"
            return MediaResult(chosenUrl, fileName)
        }
    }

    private fun resolveYouTube(url: String, isAudio: Boolean, resolution: String): MediaResult {
        val videoId = when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/")
            url.contains("youtube.com/shorts/") -> url.substringAfter("shorts/").substringBefore("?").substringBefore("/")
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            else -> throw Exception("Tautan YouTube tidak valid")
        }

        val instances = arrayOf(
            "https://yt.chocolatemoo53.com",
            "https://invidious.nerdvpn.de",
            "https://inv.tux.pizza",
            "https://invidious.jing.rocks"
        )

        for (host in instances) {
            try {
                val reqUrl = "$host/api/v1/videos/$videoId"
                val req = Request.Builder().url(reqUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)

                        if (isAudio) {
                            val adaptive = json.optJSONArray("adaptiveFormats")
                            if (adaptive != null) {
                                for (i in 0 until adaptive.length()) {
                                    val item = adaptive.getJSONObject(i)
                                    val type = item.optString("type")
                                    if (type.contains("audio/mp4") || item.optString("container") == "m4a") {
                                        return MediaResult(item.getString("url"), "YouTube_Audio_${videoId}.mp3")
                                    }
                                }
                            }
                        }

                        val formatStreams = json.optJSONArray("formatStreams")
                        if (formatStreams != null && formatStreams.length() > 0) {
                            var bestStream = formatStreams.getJSONObject(0).getString("url")
                            var streamLabel = formatStreams.getJSONObject(0).optString("qualityLabel", "HD")

                            val targetP = when {
                                resolution.contains("1080") || resolution.contains("BEST") -> "1080"
                                resolution.contains("720") -> "720"
                                resolution.contains("480") -> "480"
                                else -> "360"
                            }

                            for (i in 0 until formatStreams.length()) {
                                val item = formatStreams.getJSONObject(i)
                                val qLabel = item.optString("qualityLabel")
                                if (qLabel.contains(targetP)) {
                                    bestStream = item.getString("url")
                                    streamLabel = qLabel
                                    break
                                }
                            }

                            val ext = if (isAudio) ".mp3" else ".mp4"
                            return MediaResult(bestStream, "YouTube_${streamLabel}_${videoId}${ext}")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        throw Exception("Gagal mengekstrak YouTube. Coba link YouTube Shorts atau video lain.")
    }

    private suspend fun downloadStream(downloadUrl: String, fileName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val targetFile = File(downloadsDir, fileName)

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: Gagal mengunduh stream.")
            val body = response.body ?: throw Exception("Response body null.")

            val contentLength = body.contentLength()
            var bytesCopied: Long = 0

            withContext(Dispatchers.Main) {
                progressBar.isIndeterminate = (contentLength <= 0)
                if (contentLength > 0) {
                    progressBar.max = 100
                }
            }

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes = input.read(buffer)
                    var lastUpdate = System.currentTimeMillis()

                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250 && contentLength > 0) {
                            lastUpdate = now
                            val progress = ((bytesCopied * 100) / contentLength).toInt()
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                                percentTextView.text = "$progress%"
                                statusTextView.text = "> DOWNLOADING: $progress%\n> ${(bytesCopied / 1024)} KB / ${(contentLength / 1024)} KB"
                            }
                        }

                        bytes = input.read(buffer)
                    }
                }
            }

            // Scan to Android MediaStore so it appears in Gallery/Videos
            MediaScannerConnection.scanFile(
                this@MainActivity,
                arrayOf(targetFile.absolutePath),
                null
            ) { _, _ -> }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                percentTextView.visibility = View.VISIBLE
                percentTextView.text = "100% DONE"
                downloadButton.isEnabled = true
                downloadAudioButton.isEnabled = true
                log("> SUCCESS!\n> File Tersimpan di:\n  ${targetFile.absolutePath}\n> Buka aplikasi Galeri / File Manager!")
                Toast.makeText(this@MainActivity, "Download Selesai!\nTersimpan di Downloads", Toast.LENGTH_LONG).show()
            }
        }
    }
}


