package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusNetwork
import okhttp3.Request

class ServusPlaybackResolver(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val sessionStore = ServusSessionStore(context.applicationContext, api)

    suspend fun resolve(contentId: String): String {
        val firstSession = sessionStore.get()
        buildVodUrl(contentId, firstSession.token).takeIf { verifyManifest(it) }?.let { return it }

        val dynamicId = api.dynamicProduct(firstSession.countryCode, contentId)
            .links
            .firstOrNull { it.action == "play" }
            ?.id
            ?.takeIf { it.isNotBlank() }
        if (dynamicId != null) {
            buildVodUrl(dynamicId, firstSession.token).takeIf { verifyManifest(it) }?.let { return it }
        }

        sessionStore.clear()
        val renewed = sessionStore.get(forceRefresh = true)
        buildVodUrl(dynamicId ?: contentId, renewed.token).takeIf { verifyManifest(it) }?.let { return it }

        error("ServusTV HLS-Stream ist aktuell nicht verfügbar")
    }

    private fun verifyManifest(url: String): Boolean {
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            ServusNetwork.httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.isRedirect
            }
        }.getOrDefault(false)
    }

    companion object {
        fun buildVodUrl(contentId: String, token: String): String =
            "${ServusNetwork.DMS_BASE_URL}$contentId/$token/playlist.m3u8?namespace=stv"
    }
}
