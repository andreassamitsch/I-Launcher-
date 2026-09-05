package com.andreassamitsch.servusprovider.data

import android.content.Context
import com.andreassamitsch.servusprovider.api.ServusApi
import com.andreassamitsch.servusprovider.api.ServusNetwork
import okhttp3.Request
import java.util.Locale

class ServusPlaybackResolver(
    context: Context,
    private val api: ServusApi = ServusNetwork.api,
) {
    private val appContext = context.applicationContext
    private val sessionStore = ServusSessionStore(appContext, api)
    private val hubStore = ServusHubStore(appContext)

    suspend fun resolve(contentId: String): String {
        hubStore.findLiveChannel(contentId)?.let { live ->
            return resolveLive(live)
        }
        return resolveVod(contentId)
    }

    private suspend fun resolveVod(contentId: String): String {
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

    private suspend fun resolveLive(channel: ServusLiveChannel): String {
        val firstSession = sessionStore.get()
        buildLiveUrl(channel, firstSession).takeIf { verifyManifest(it) }?.let { return it }

        val dynamicId = runCatching { api.dynamicProduct(firstSession.countryCode, channel.id) }
            .getOrNull()
            ?.links
            .orEmpty()
            .firstOrNull { it.action == "play" }
            ?.id
            ?.takeIf { it.isNotBlank() }
        if (dynamicId != null) {
            buildVodUrl(dynamicId, firstSession.token).takeIf { verifyManifest(it) }?.let { return it }
        }

        sessionStore.clear()
        val renewed = sessionStore.get(forceRefresh = true)
        buildLiveUrl(channel, renewed).takeIf { verifyManifest(it) }?.let { return it }
        error("ServusTV Live-Stream ist aktuell nicht verfügbar")
    }

    private fun buildLiveUrl(channel: ServusLiveChannel, session: ServusSession): String =
        if (isMainServusLive(channel.title)) {
            "${ServusNetwork.DMS_BASE_URL}stv-linear/${session.token}/playlist.m3u8?namespace=stv"
        } else {
            buildLiveDestinationUrl(channel.id, session.countryCode)
        }

    private fun verifyManifest(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ServusNetwork.WEB_USER_AGENT)
            .header("Referer", "https://www.servustv.com/")
            .get()
            .build()
        return runCatching {
            ServusNetwork.httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.isRedirect
            }
        }.getOrDefault(false)
    }

    companion object {
        fun buildVodUrl(contentId: String, token: String): String =
            "${ServusNetwork.DMS_BASE_URL}$contentId/$token/playlist.m3u8?namespace=stv"

        fun buildLiveDestinationUrl(channelId: String, market: String): String =
            "${ServusNetwork.DMS_BASE_URL}destination/stv/$channelId/personal_computer/http/de/$market/playlist.m3u8"

        fun isMainServusLive(title: String): Boolean {
            val value = title.lowercase(Locale.GERMAN)
            return value.contains("servustv") && (value.contains(" live") || value.contains("livestream"))
        }
    }
}
