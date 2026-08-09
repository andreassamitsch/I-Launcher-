package com.andreassamitsch.ilauncher.data.epg

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

internal class EpgNetworkClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadM3u(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/x-mpegURL,text/plain,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EpgHttpException(response.code)
            val body = response.body
            val length = body.contentLength()
            if (length > MAX_M3U_BYTES) error("EPG M3U exceeds size limit")
            val text = body.string()
            check(text.toByteArray(Charsets.UTF_8).size <= MAX_M3U_BYTES) {
                "EPG M3U exceeds size limit"
            }
            return text
        }
    }

    fun <T> readXmlTv(url: String, block: (InputStream) -> T): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/gzip,application/xml,text/xml,*/*")
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EpgHttpException(response.code)
            val body = response.body
            val length = body.contentLength()
            if (length > MAX_COMPRESSED_XMLTV_BYTES) error("XMLTV download exceeds size limit")
            val buffered = BufferedInputStream(body.byteStream(), 32 * 1024)
            val stream = if (buffered.isGzip()) GZIPInputStream(buffered, 32 * 1024) else buffered
            stream.use { return block(it) }
        }
    }

    private fun BufferedInputStream.isGzip(): Boolean {
        mark(2)
        val first = read()
        val second = read()
        reset()
        return first == 0x1f && second == 0x8b
    }

    companion object {
        private const val MAX_M3U_BYTES = 2L * 1024L * 1024L
        private const val MAX_COMPRESSED_XMLTV_BYTES = 32L * 1024L * 1024L
    }
}

internal class EpgHttpException(val statusCode: Int) : Exception("HTTP $statusCode")
