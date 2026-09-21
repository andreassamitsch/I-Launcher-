package com.andreassamitsch.joyntv

import android.content.Context
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The API may return a Joyn episode still using a small /profile:...503x283 rendition. The
 * original image path is taken ONLY from that exact API image URL: never guess another asset ID,
 * never re-use art from a similarly named series and never claim a 4K rendition without reading
 * the remote image header. All probing runs on Dispatchers.IO; the publisher keeps its API URL
 * unless the original image is confirmed to have more actual pixels.
 */
internal class JoynHighResEpisodeArtwork(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("joyn_hi_res_stills_v1", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun cachedUrl(apiUrl: String?): String? {
        val key = apiUrl?.let(::keyFor) ?: return null
        return prefs.getString("image_$key", null)
    }

    fun shouldProbe(apiUrl: String?): Boolean {
        val url = apiUrl ?: return false
        if (originalCandidate(url) == null || cachedUrl(url) != null) return false
        return System.currentTimeMillis() >= prefs.getLong("retry_${keyFor(url)}", 0L)
    }

    suspend fun validatedOriginal(apiUrl: String): String? = withContext(Dispatchers.IO) {
        cachedUrl(apiUrl)?.let { return@withContext it }
        if (!shouldProbe(apiUrl)) return@withContext null
        val original = originalCandidate(apiUrl) ?: return@withContext null
        val sourceSize = dimensionsFromProfile(apiUrl)
        val verified = runCatching {
            val request = Request.Builder()
                .url(original)
                .header("Range", "bytes=0-98303")
                .header("Accept", "image/jpeg,image/png,image/webp")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/") != true) {
                    return@use false
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (bytes.size() < MAX_HEADER_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_HEADER_BYTES - bytes.size()))
                        if (read <= 0) break
                        bytes.write(buffer, 0, read)
                    }
                }
                val header = bytes.toByteArray()
                BitmapFactory.decodeByteArray(header, 0, header.size, options)
                val width = options.outWidth
                val height = options.outHeight
                width >= 960 && height >= 540 && width <= 8192 && height <= 8192 &&
                    (sourceSize == null || (width > sourceSize.first && height > sourceSize.second))
            }
        }.getOrDefault(false)
        val key = keyFor(apiUrl)
        if (verified) {
            prefs.edit().putString("image_$key", original).remove("retry_$key").apply()
            original
        } else {
            prefs.edit().putLong("retry_$key", System.currentTimeMillis() + FAILED_RETRY_MS).apply()
            null
        }
    }

    companion object {
        private const val MAX_HEADER_BYTES = 96 * 1024
        private const val FAILED_RETRY_MS = 6L * 60L * 60L * 1000L
        private val sizePattern = Regex("(\\d{2,5})x(\\d{2,5})")

        /** Strip only the Joyn transformation of the *same* API image; never change its ingest ID. */
        internal fun originalCandidate(apiUrl: String): String? {
            val parsed = apiUrl.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.host != "img.joyn.de") return null
            val path = parsed.encodedPath
            if (!path.startsWith("/ingest/") || !path.contains("/profile:")) return null
            val imagePath = path.substringBeforeLast("/profile:")
            if (!imagePath.matches(Regex("/ingest/[A-Za-z0-9_./-]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))) return null
            val profile = path.substringAfterLast("/profile:")
            if (!profile.matches(Regex("[A-Za-z0-9_-]{4,90}"))) return null
            return parsed.newBuilder().encodedPath(imagePath).build().toString()
        }

        private fun dimensionsFromProfile(url: String): Pair<Int, Int>? {
            val value = sizePattern.find(url.substringAfterLast("/profile:", "")) ?: return null
            return value.groupValues[1].toIntOrNull()?.let { width ->
                value.groupValues[2].toIntOrNull()?.let { height -> width to height }
            }
        }

        private fun keyFor(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }
}
