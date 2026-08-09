package com.andreassamitsch.ilauncher.data.openwebif

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Resolves the receiver-owned OpenWebif stream playlist to one ephemeral playback URL.
 * The returned URL and any session/auth headers are intentionally never persisted or logged.
 */
internal class OpenWebifStreamResolver {
    fun resolve(
        config: OpenWebifConfig,
        serviceReference: String,
        channelName: String,
    ): OpenWebifResolvedStream {
        require(serviceReference.isNotBlank()) { "Missing service reference" }

        val endpoint = config.baseUrl.toHttpUrl()
            .resolve("web/stream.m3u")
            ?.newBuilder()
            ?.addQueryParameter("ref", serviceReference)
            ?.addQueryParameter("name", channelName)
            ?.addQueryParameter("fname", channelName)
            ?.build()
            ?: throw OpenWebifStreamException("OpenWebif stream endpoint is invalid")

        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/x-mpegURL,text/plain,*/*")
            .build()

        OpenWebifNetworkClient.createHttpClient(config).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OpenWebifStreamHttpException(response.code)
            }
            val body = response.body
            val length = body.contentLength()
            if (length > MAX_PLAYLIST_BYTES) {
                throw OpenWebifStreamException("OpenWebif stream playlist exceeds size limit")
            }
            val text = body.string()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_PLAYLIST_BYTES) {
                throw OpenWebifStreamException("OpenWebif stream playlist exceeds size limit")
            }
            return OpenWebifStreamPlaylist.parse(text)
                ?: throw OpenWebifStreamException("OpenWebif returned no playable stream URL")
        }
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 64L * 1024L
    }
}

internal data class OpenWebifResolvedStream(
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    val isHls: Boolean
        get() = url.toHttpUrlOrNull()
            ?.encodedPath
            ?.lowercase()
            ?.endsWith(".m3u8") == true
}

internal object OpenWebifStreamPlaylist {
    fun parse(text: String): OpenWebifResolvedStream? {
        val rawUrl = text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith('#') }
            ?: return null

        val parsed = rawUrl.toHttpUrlOrNull() ?: return null
        val headers = linkedMapOf<String, String>()
        if (parsed.username.isNotEmpty()) {
            headers["Authorization"] = Credentials.basic(parsed.username, parsed.password)
        }

        val sanitized = parsed.newBuilder()
            .username("")
            .password("")
            .build()
            .toString()

        return OpenWebifResolvedStream(
            url = sanitized,
            requestHeaders = headers,
        )
    }
}

internal open class OpenWebifStreamException(message: String) : Exception(message)
internal class OpenWebifStreamHttpException(val statusCode: Int) :
    OpenWebifStreamException("HTTP $statusCode")
