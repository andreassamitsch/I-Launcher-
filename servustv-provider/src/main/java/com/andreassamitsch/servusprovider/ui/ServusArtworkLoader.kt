package com.andreassamitsch.servusprovider.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.andreassamitsch.servusprovider.R
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.data.ServusBranding
import kotlin.math.roundToInt
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
    private const val MIN_TITLE_TREATMENT_HEIGHT_DP = 56
    private val decodeSemaphore = Semaphore(3)

    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(scope: CoroutineScope, imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return

        if (isTitleTreatment(url)) {
            prepareTitleTreatmentView(imageView)
        }

        if (ServusBranding.isNinetySecondLogoUri(url)) {
            // The 90-second logo is part of this APK. Never make ServusTV's own UI depend on the
            // exported ContentProvider that exists only to transport branding through TvProvider.
            // Recognising the legacy resource URI also keeps pre-update cached catalogue rows safe.
            imageView.tag = url
            imageView.setImageResource(R.drawable.servus_news_90_logo)
            return
        }

        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (
            uri?.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE ||
            uri?.scheme == ContentResolver.SCHEME_CONTENT
        ) {
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

    private fun isTitleTreatment(url: String): Boolean =
        ServusBranding.isNinetySecondLogoUri(url) ||
            url.contains("title_treatment", ignoreCase = true) ||
            url.contains("title-treatment", ignoreCase = true) ||
            url.contains("treatment", ignoreCase = true) ||
            url.contains("wordmark", ignoreCase = true) ||
            url.contains("_logo", ignoreCase = true) ||
            url.contains("/logo", ignoreCase = true)

    /**
     * Title treatments, wordmarks and show logos are logos, not artwork crops. Some ServusTV
     * treatments (notably Servus Wetter) are considerably taller than the generic news wordmark.
     * Keep their full aspect ratio and give compact cards enough vertical room instead of squeezing
     * them into the old 34/42dp slot where script treatments appeared visually clipped.
     */
    private fun prepareTitleTreatmentView(imageView: ImageView) {
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.adjustViewBounds = false
        val padding = (4 * imageView.resources.displayMetrics.density).roundToInt()
        imageView.setPadding(0, padding, 0, padding)

        val params = imageView.layoutParams ?: return
        val minHeight = (MIN_TITLE_TREATMENT_HEIGHT_DP * imageView.resources.displayMetrics.density).roundToInt()
        if (params.height in 1 until minHeight) {
            params.height = minHeight
            imageView.layoutParams = params
            imageView.requestLayout()
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
