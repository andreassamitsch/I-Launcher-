package com.andreassamitsch.joyntv

import android.content.Context

internal class JoynRepository(context: Context) {
    private val appContext = context.applicationContext.also {
        JoynRegionSettings.install(it)
        JoynProxySettings.install(it)
    }
    private val protocolPrefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val publicProxyResolver = JoynPublicProxyResolver()
    private val nordVpnProxyResolver = JoynNordVpnProxyResolver()
    private val pinSettings = JoynParentalPinSettings(appContext)
    private val api = JoynApiClient(appContext)
    private val pinPlaybackApi = JoynPinPlaybackApiClient(appContext)
    private val browseApi = JoynBrowseApiClient(appContext)
    private val categoryApi = JoynCategoryApiClient(appContext)
    private val collectionApi = JoynCollectionApiClient(appContext)
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

    // Keep category opening deliberately close to the Kodi reference: the category card carries
    // Joyn's original block id and the click resolves that id once through LandingBlocks. Do not
    // let the generic browse cache replace the original lane with a lightweight block shell.
    suspend fun loadCategory(blockId: String, title: String): JoynCataloguePage =
        categoryApi.loadCategory(blockId, title)

    suspend fun loadChannel(path: String, title: String): JoynCataloguePage =
        browseApi.loadChannel(path, title)

    // Teasers/genre entries are collection pages. Kodi treats StandardLane blocks in a collection
    // as another folder and only renders Grid assets directly. Keep that semantic in one dedicated
    // resolver instead of silently returning an empty generic browse page.
    suspend fun loadCollection(path: String, title: String): JoynCataloguePage =
        collectionApi.loadCollection(path, title)

    suspend fun loadCompilation(path: String, title: String): JoynCataloguePage =
        browseApi.loadCompilation(path, title)

    suspend fun searchMedia(text: String): List<JoynMediaItem> = api.searchMedia(text)

    suspend fun loadSeriesDetails(item: JoynMediaItem): JoynSeriesDetails =
        api.loadSeriesDetails(item)

    suspend fun loadSeasonEpisodes(seasonId: String): List<JoynMediaItem> =
        api.loadSeasonEpisodes(seasonId)

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback =
        api.resolveLivePlayback(channelId)

    suspend fun resolveVodPlayback(contentRef: String, enteredPin: String? = null): JoynPlayback {
        val explicitPin = enteredPin?.takeIf { it.matches(Regex("^\\d{4}$")) }
        if (explicitPin != null) {
            if (!api.accountState(refreshRemote = false).loggedIn) throw JoynLoginRequiredException()
            return pinPlaybackApi.resolveVodPlayback(contentRef, explicitPin)
        }

        // Keep the proven playback/session-recovery path for every VOD request. Joyn expects
        // the PIN only after the entitlement service explicitly answers with ENT_PINRequired.
        return try {
            api.resolveVodPlayback(contentRef)
        } catch (error: Throwable) {
            val details = error.message.orEmpty()

            if (details.contains("ENT_AgeVerificationSetupRequired", ignoreCase = true) &&
                !api.accountState(refreshRemote = false).loggedIn
            ) {
                throw JoynLoginRequiredException()
            }

            if (!details.contains("ENT_PINRequired", ignoreCase = true)) throw error

            if (!api.accountState(refreshRemote = false).loggedIn) throw JoynLoginRequiredException()

            val automaticPin = if (pinSettings.autoUse()) pinSettings.readPin() else null
            if (automaticPin != null) {
                pinPlaybackApi.resolveVodPlayback(contentRef, automaticPin)
            } else {
                throw JoynPinRequiredException()
            }
        }
    }

    fun hasStoredParentalPin(): Boolean = pinSettings.hasPin()

    fun parentalPinAutoUse(): Boolean = pinSettings.autoUse()

    fun saveParentalPin(pin: String, autoUse: Boolean = true) = pinSettings.save(pin, autoUse)

    fun setParentalPinAutoUse(enabled: Boolean) = pinSettings.setAutoUse(enabled)

    fun clearParentalPin() = pinSettings.clear()

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

    suspend fun findAutomaticProxy(
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        val country = currentCountry()
        return publicProxyResolver.findBest(
            country = country,
            allTraffic = allTraffic,
            apiKey = protocolPrefs.getString("api_key_${country.name}", null),
            onProgress = onProgress,
        )
    }

    fun nordVpnServiceUsername(): String =
        protocolPrefs.getString(KEY_NORD_USERNAME, "").orEmpty()

    fun nordVpnServicePassword(): String =
        protocolPrefs.getString(KEY_NORD_PASSWORD, "").orEmpty()

    fun saveNordVpnServiceCredentials(username: String, password: String) {
        protocolPrefs.edit()
            .putString(KEY_NORD_USERNAME, username.trim())
            .putString(KEY_NORD_PASSWORD, password)
            .apply()
    }

    suspend fun findNordVpnProxy(
        username: String,
        password: String,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        val country = currentCountry()
        return try {
            nordVpnProxyResolver.findBest(
                country = country,
                allTraffic = allTraffic,
                apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                username = username,
                password = password,
                onProgress = onProgress,
            )
        } finally {
            // SOCKS authentication is process-global in java.net on Android. Restore the currently
            // persisted Joyn proxy after the isolated Nord server scan has finished.
            JoynProxySettings.install(appContext)
        }
    }

    fun setProxy(config: JoynProxyConfig) {
        proxySettings.save(config)
    }

    private companion object {
        private const val KEY_NORD_USERNAME = "nordvpn_service_username"
        private const val KEY_NORD_PASSWORD = "nordvpn_service_password"
    }
}

internal class JoynLoginRequiredException : Exception(
    "Für diesen geschützten Inhalt musst du in Joyn TV mit deinem Joyn-Konto angemeldet sein.",
)
