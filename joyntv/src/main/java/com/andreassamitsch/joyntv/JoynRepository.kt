package com.andreassamitsch.joyntv

import android.content.Context

internal class JoynRepository(context: Context) {
    private val appContext = context.applicationContext.also {
        JoynRegionSettings.install(it)
        JoynProxySettings.install(it)
    }
    private val regionSettings = JoynRegionSettings(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val api = JoynApiClient(appContext)
    private val browseApi = JoynBrowseApiClient(appContext)
    private val categoryApi = JoynCategoryApiClient(appContext)
    private val previewPublisher = JoynPreviewChannelPublisher(appContext)

    suspend fun loadLiveChannelsAndPublish(): List<JoynLiveChannel> {
        val channels = api.loadLiveChannels()
        previewPublisher.publishLive(channels)
        return channels
    }

    suspend fun loadCatalogue(path: String = "/neu-beliebt"): JoynCataloguePage {
        val page = api.loadCatalogue(path)
        if (path != "/neu-beliebt") return page

        val browseLanes = buildList {
            runCatching { browseApi.loadMediaLibraries() }.getOrNull()?.lanes?.let(::addAll)
            runCatching { browseApi.loadCategories("/") }.getOrNull()?.lanes?.let(::addAll)
        }
        return page.copy(lanes = browseLanes + page.lanes)
    }

    suspend fun loadCategory(blockId: String, title: String): JoynCataloguePage =
        categoryApi.loadCategory(blockId, title)

    suspend fun loadChannel(path: String, title: String): JoynCataloguePage =
        browseApi.loadChannel(path, title)

    suspend fun loadCollection(path: String, title: String): JoynCataloguePage =
        browseApi.loadCollection(path, title)

    suspend fun loadCompilation(path: String, title: String): JoynCataloguePage =
        browseApi.loadCompilation(path, title)

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

    fun currentCountry(): JoynCountry = regionSettings.currentCountry()

    fun selectedCountry(): JoynCountry? = regionSettings.selectedCountry()

    fun countryIsAutomatic(): Boolean = regionSettings.isAutomatic()

    fun setCountry(country: JoynCountry?) {
        regionSettings.setCountry(country)
    }

    fun proxyConfig(): JoynProxyConfig = proxySettings.current()

    fun setProxy(config: JoynProxyConfig) {
        proxySettings.save(config)
    }
}
