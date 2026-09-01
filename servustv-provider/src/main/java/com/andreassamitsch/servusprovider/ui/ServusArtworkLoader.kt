package com.andreassamitsch.servusprovider.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.andreassamitsch.servusprovider.api.ServusNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

object ServusArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(scope: CoroutineScope, imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        imageView.tag = url
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { download(url) } ?: return@launch
            cache.put(url, bitmap)
            if (imageView.tag == url) imageView.setImageBitmap(bitmap)
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ServusNetwork.WEB_USER_AGENT)
            .header("Referer", "https://www.servustv.com/")
            .build()
        ServusNetwork.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            BitmapFactory.decodeStream(response.body.byteStream())
        }
    }.getOrNull()
}
