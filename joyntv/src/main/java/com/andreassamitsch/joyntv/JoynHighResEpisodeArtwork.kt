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
 * Upgrade an episode image ONLY using the exact image identity returned by Joyn's API.
 * A small LIVE_STILL may have a much better 1920x1080 PRIMARYCUT rendition even when the
 * unprofiled original is unavailable. Both are checked before a Watch Next image is replaced.
 * The image response (not its URL/profile name) determines the actual pixel dimensions.
 */
internal class JoynHighResEpisodeArtwork(context: Context) {
    // V2 intentionally rechecks images previously cached as unprofiled originals by V1.
    private val prefs = context.applicationContext.getSharedPreferences("joyn_hi_res_stills_v2", Context.MODE_PRIVATE)
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
        if (renditionCandidates(url).isEmpty() || cachedUrl(url) != null) return false
        return System.currentTimeMillis() >= prefs.getLong("retry_${keyFor(url)}", 0L)
    }

    /** Existing publisher contract: returns the best verified larger rendition, not necessarily
     * an unprofiled original. Network access is restricted to Dispatchers.IO. */
    suspend fun validatedOriginal(apiUrl: String): String? = withContext(Dispatchers.IO) {
        cachedUrl(apiUrl)?.let { return@withContext it }
        if (!shouldProbe(apiUrl)) return@withContext null
        val candidates = renditionCandidates(apiUrl)
        if (candidates.isEmpty()) return@withContext null
        val sourceSize = dimensionsFromProfile(apiUrl)
        var best: VerifiedStill? = null
        for (candidate in candidates) {
            val dimensions = readImageDimensions(candidate) ?: continue
            if (!isLargerHeroImage(dimensions, sourceSize)) continue
            val image = VerifiedStill(candidate, dimensions.first, dimensions.second)
            if (best == null || image.pixelCount > best.pixelCount) best = image
        }
        val key = keyFor(apiUrl)
        if (best != null) {
            prefs.edit().putString("image_$key", best.url).remove("retry_$key").apply()
            best.url
        } else {
            // Do not retry a missing profile on every UI refresh, but allow a later CDN retry.
            prefs.edit().putLong("retry_$key", System.currentTimeMillis() + FAILED_RETRY_MS).apply()
            null
        }
    }

    private fun readImageDimensions(candidate: String): Pair<Int, Int>? = runCatching {
        val request = Request.Builder()
            .url(candidate)
            .header("Range", "bytes=0-98303")
            .header("Accept", "image/jpeg,image/png,image/webp")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful ||
                response.header("Content-Type")?.startsWith("image/", ignoreCase = true) != true
            ) return@use null
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
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(header, 0, header.size, options)
            options.outWidth.takeIf { it > 0 }?.let { width ->
                options.outHeight.takeIf { it > 0 }?.let { height -> width to height }
            }
        }
    }.getOrNull()

    private data class VerifiedStill(val url: String, val width: Int, val height: Int) {
        val pixelCount: Long get() = width.toLong() * height
    }

    companion object {
        private const val MAX_HEADER_BYTES = 96 * 1024
        private const val FAILED_RETRY_MS = 6L * 60L * 60L * 1000L
        private const val PRIMARYCUT_PROFILE = "nextgen-web-primarycut-1920x1080"
        private val sizePattern = Regex("(\\d{2,5})x(\\d{2,5})")
        private val imagePathPattern = Regex(
            "/ingest/[A-Za-z0-9_./-]+\\.(jpg|jpeg|png|webp)",
            RegexOption.IGNORE_CASE,
        )
        private val profilePattern = Regex("[A-Za-z0-9_-]{4,90}")

        /** This candidate is the SAME image asset as the API URL, never another episode's art. */
        internal fun primarycutCandidate(apiUrl: String): String? {
            val original = originalCandidate(apiUrl)?.toHttpUrlOrNull() ?: return null
            return original.newBuilder()
                .encodedPath("${original.encodedPath}/profile:$PRIMARYCUT_PROFILE")
                .build()
                .toString()
        }

        /** Try the verified Full-HD crop first, then the unprofiled original for possible 4K.
         * Both preserve the API image ID, filename, host and query parameters unchanged. */
        internal fun renditionCandidates(apiUrl: String): List<String> {
            val original = originalCandidate(apiUrl) ?: return emptyList()
            return listOfNotNull(primarycutCandidate(apiUrl), original)
                .filterNot { it == apiUrl }
                .distinct()
        }

        /** Strip only the Joyn transformation of the same API image. */
        internal fun originalCandidate(apiUrl: String): String? {
            val parsed = apiUrl.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.host != "img.joyn.de") return null
            val path = parsed.encodedPath
            if (!path.startsWith("/ingest/") || !path.contains("/profile:")) return null
            val imagePath = path.substringBeforeLast("/profile:")
            if (!imagePath.matches(imagePathPattern)) return null
            val profile = path.substringAfterLast("/profile:")
            if (!profile.matches(profilePattern)) return null
            return parsed.newBuilder().encodedPath(imagePath).build().toString()
        }

        private fun dimensionsFromProfile(url: String): Pair<Int, Int>? {
            val value = sizePattern.find(url.substringAfterLast("/profile:", "")) ?: return null
            return value.groupValues[1].toIntOrNull()?.let { width ->
                value.groupValues[2].toIntOrNull()?.let { height -> width to height }
            }
        }

        internal fun isLargerHeroImage(
            candidateSize: Pair<Int, Int>,
            sourceSize: Pair<Int, Int>?,
        ): Boolean {
            val (width, height) = candidateSize
            if (width < 960 || height < 540 || width > 8192 || height > 8192) return false
            // Hero artwork must be wide. A large portrait original is not a substitute for a
            // correctly cropped 16:9 still and could otherwise beat the valid 1920x1080 cut.
            if (width.toDouble() / height !in 1.5..2.1) return false
            return sourceSize == null || width.toLong() * height >
                sourceSize.first.toLong() * sourceSize.second
        }

        private fun keyFor(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }
}
