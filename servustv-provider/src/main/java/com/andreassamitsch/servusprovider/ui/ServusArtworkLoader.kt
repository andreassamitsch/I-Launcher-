package com.andreassamitsch.servusprovider.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.andreassamitsch.servusprovider.api.ServusNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

object ServusArtworkLoader {
    private const val DEFAULT_TARGET_PX = 640
    private const val MIN_TARGET_PX = 256
    private const val MAX_TARGET_PX = 1024
    private val decodeSemaphore = Semaphore(3)

    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(scope: CoroutineScope, imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return

        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (
            uri?.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE ||
            uri?.scheme == ContentResolver.SCHEME_CONTENT
        ) {
            // Bundled/cross-process local branding must never go through OkHttp. The content URI is
            // also the exact URI passed to TvProvider, so ServusTV and I Launcher render one source.
            imageView.tag = url
            imageView.setImageURI(uri)
            return
        }

        val targetPx = targetDimension(imageView)
        val cacheKey = "$url#$targetPx"
        imageView.tag = cacheKey
        cache.get(cacheKey)?.let {
            imageView.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSemaphore.withPermit { download(url, targetPx) }
            } ?: return@launch
            cache.put(cacheKey, bitmap)
            if (imageView.tag == cacheKey) imageView.setImageBitmap(bitmap)
        }
    }

    private fun targetDimension(imageView: ImageView): Int {
        val params = imageView.layoutParams
        val candidate = listOf(params?.width ?: 0, params?.height ?: 0)
            .filter { it > 0 }
            .maxOrNull()
            ?: DEFAULT_TARGET_PX
        return (candidate * 2).coerceIn(MIN_TARGET_PX, MAX_TARGET_PX)
    }

    private fun download(url: String, targetPx: Int): Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ServusNetwork.WEB_USER_AGENT)
            .header("Referer", "https://www.servustv.com/")
            .build()
        ServusNetwork.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (largest > 0 && largest / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }.getOrNull()
}
