package com.andreassamitsch.servusprovider.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.andreassamitsch.servusprovider.R
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.data.ServusBranding
import com.andreassamitsch.servusprovider.data.ServusCatalogPolicy
import com.andreassamitsch.servusprovider.data.ServusHubStore
import com.andreassamitsch.servusprovider.data.ServusSessionStore
import java.util.concurrent.ConcurrentHashMap
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
    private const val MIN_TITLE_TREATMENT_HEIGHT_DP = 88
    private val decodeSemaphore = Semaphore(3)
    private val lazyLogoSemaphore = Semaphore(3)
    private val resolvedLazyLogos = ConcurrentHashMap<String, String>()
    private val missingLazyLogos = ConcurrentHashMap.newKeySet<String>()

    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(scope: CoroutineScope, imageView: ImageView, url: String?) {
        val normalizedUrl = ServusBranding.normalizeLogoUri(url) ?: return

        if (ServusBranding.isLazyLogoUri(normalizedUrl)) {
            loadLazyShowLogo(scope, imageView, normalizedUrl)
            return
        }

        imageView.visibility = View.VISIBLE
        if (isTitleTreatment(normalizedUrl)) {
            prepareTitleTreatmentView(imageView)
        }

        if (ServusBranding.isNinetySecondLogoUri(normalizedUrl)) {
            // The 90-second logo is part of this APK. Never make ServusTV's own UI depend on the
            // exported ContentProvider that exists only to transport branding through TvProvider.
            // Recognising the legacy resource URI also keeps pre-update cached catalogue rows safe.
            imageView.tag = normalizedUrl
            imageView.setImageResource(R.drawable.servus_news_90_logo)
            return
        }

        val uri = runCatching { Uri.parse(normalizedUrl) }.getOrNull()
        if (
            uri?.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE ||
            uri?.scheme == ContentResolver.SCHEME_CONTENT
        ) {
            imageView.tag = normalizedUrl
            imageView.setImageURI(uri)
            return
        }

        val targetPx = targetDimension(imageView)
        val cacheKey = "$normalizedUrl#$targetPx"
        imageView.tag = cacheKey
        cache.get(cacheKey)?.let {
            imageView.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSemaphore.withPermit { download(normalizedUrl, targetPx) }
            } ?: return@launch
            cache.put(cacheKey, bitmap)
            if (imageView.tag == cacheKey) imageView.setImageBitmap(bitmap)
        }
    }

    /**
     * Missing catalogue branding is resolved from the official product endpoint only when the logo
     * view actually enters the visible viewport. This keeps the hub Local First and avoids fetching
     * product details for every off-screen show merely because a HorizontalScrollView created its
     * child view.
     */
    private fun loadLazyShowLogo(
        scope: CoroutineScope,
        imageView: ImageView,
        lazyUrl: String,
    ) {
        val showId = ServusBranding.showIdFromLazyLogoUri(lazyUrl) ?: return
        resolvedLazyLogos[showId]?.let { resolved ->
            load(scope, imageView, resolved)
            return
        }
        if (showId in missingLazyLogos) {
            imageView.visibility = View.GONE
            return
        }

        imageView.tag = lazyUrl
        var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var started = false

        fun cleanup() {
            scrollListener?.let { listener ->
                val observer = imageView.viewTreeObserver
                if (observer.isAlive) observer.removeOnScrollChangedListener(listener)
            }
            attachListener?.let(imageView::removeOnAttachStateChangeListener)
            scrollListener = null
            attachListener = null
        }

        fun startIfVisible() {
            if (started) return
            if (imageView.tag != lazyUrl) {
                cleanup()
                return
            }
            val visibleRect = Rect()
            if (!imageView.isShown || !imageView.getGlobalVisibleRect(visibleRect)) return
            if (visibleRect.width() <= 0 || visibleRect.height() <= 0) return

            started = true
            cleanup()
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    resolveShowLogo(imageView.context.applicationContext, showId)
                }
                if (imageView.tag != lazyUrl) return@launch
                if (resolved == null) {
                    imageView.visibility = View.GONE
                } else {
                    imageView.visibility = View.VISIBLE
                    load(scope, imageView, resolved)
                }
            }
        }

        scrollListener = ViewTreeObserver.OnScrollChangedListener { startIfVisible() }
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                imageView.post { startIfVisible() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                cleanup()
            }
        }
        imageView.addOnAttachStateChangeListener(requireNotNull(attachListener))
        val observer = imageView.viewTreeObserver
        if (observer.isAlive) observer.addOnScrollChangedListener(requireNotNull(scrollListener))
        imageView.post { startIfVisible() }
    }

    private suspend fun resolveShowLogo(context: Context, showId: String): String? {
        resolvedLazyLogos[showId]?.let { return it }
        if (showId in missingLazyLogos) return null

        return lazyLogoSemaphore.withPermit {
            resolvedLazyLogos[showId]?.let { return@withPermit it }
            if (showId in missingLazyLogos) return@withPermit null

            val resolved = runCatching {
                val api = ServusNetwork.api
                val session = ServusSessionStore(context, api).get()
                val detail = api.product(session.countryCode, showId)
                ServusCatalogPolicy.titleTreatment(showId, detail.mediaResources)
                    ?.let(ServusBranding::normalizeLogoUri)
            }.getOrNull()

            if (resolved.isNullOrBlank()) {
                missingLazyLogos += showId
                null
            } else {
                resolvedLazyLogos[showId] = resolved
                runCatching { ServusHubStore(context).updateShowLogo(showId, resolved) }
                resolved
            }
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
        val padding = (6 * imageView.resources.displayMetrics.density).roundToInt()
        imageView.setPadding(padding, padding, padding, padding)

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
