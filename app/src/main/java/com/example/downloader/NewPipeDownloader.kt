package com.example.downloader

import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

internal class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val headerMap = mutableMapOf<String, String>()
        for ((key, values) in headers) {
            if (values.isNotEmpty()) {
                headerMap[key] = values.joinToString(", ")
            }
        }
        if (!headerMap.containsKey("User-Agent") && !headerMap.containsKey("user-agent")) {
            headerMap["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }

        val requestBody = when {
            dataToSend != null -> {
                val contentType = (headerMap["Content-Type"] ?: headerMap["content-type"])?.toMediaTypeOrNull()
                dataToSend.toRequestBody(contentType)
            }
            httpMethod.equals("POST", ignoreCase = true) ||
                httpMethod.equals("PUT", ignoreCase = true) ||
                httpMethod.equals("PATCH", ignoreCase = true) -> {
                ByteArray(0).toRequestBody(null)
            }
            else -> null
        }

        val okRequest = okhttp3.Request.Builder()
            .url(url)
            .headers(headerMap.toHeaders())
            .method(httpMethod, requestBody)
            .build()

        client.newCall(okRequest).execute().use { okResponse ->
            if (okResponse.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val body = okResponse.body?.string() ?: ""
            val responseHeaders = mutableMapOf<String, MutableList<String>>()
            for (name in okResponse.headers.names()) {
                responseHeaders[name] = okResponse.headers.values(name).toMutableList()
            }
            return Response(
                okResponse.code,
                okResponse.message,
                responseHeaders,
                body,
                okResponse.request.url.toString()
            )
        }
    }
}
