package com.andreassamitsch.joyntv

import android.content.Context

internal class JoynRepository(context: Context) {
    private val api = JoynApiClient(context)
    private val previewPublisher = JoynPreviewChannelPublisher(context)

    suspend fun loadLiveChannelsAndPublish(): List<JoynLiveChannel> {
        val channels = api.loadLiveChannels()
        previewPublisher.publishLive(channels)
        return channels
    }

    suspend fun loadCatalogue(path: String = "/neu-beliebt"): JoynCataloguePage =
        api.loadCatalogue(path)

    suspend fun searchMedia(text: String): List<JoynMediaItem> = api.searchMedia(text)

    suspend fun loadSeriesDetails(item: JoynMediaItem): JoynSeriesDetails =
        api.loadSeriesDetails(item)

    suspend fun loadSeasonEpisodes(seasonId: String): List<JoynMediaItem> =
        api.loadSeasonEpisodes(seasonId)

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback =
        api.resolveLivePlayback(channelId)

    suspend fun resolveVodPlayback(videoId: String): JoynPlayback =
        api.resolveVodPlayback(videoId)

    suspend fun login(email: String, password: String): JoynAccountState =
        api.login(email, password)

    suspend fun logout() = api.logout()

    suspend fun accountState(refreshRemote: Boolean = true): JoynAccountState =
        api.accountState(refreshRemote)
}
