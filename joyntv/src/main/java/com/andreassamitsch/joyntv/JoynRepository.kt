package com.andreassamitsch.joyntv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JoynRepository(context: Context) {
    private val appContext = context.applicationContext.also {
        JoynRegionSettings.install(it)
        JoynProxySettings.install(it)
        JoynMysteriumAutoFailover.install(it)
    }
    private val protocolPrefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val mysteriumSettings = JoynMysteriumSettings(appContext)
    private val mysteriumApiClient = JoynMysteriumApiClient(appContext)
    private val mysteriumScanner = JoynMysteriumProxyScanner(appContext, mysteriumApiClient)
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

    suspend fun loadCategory(blockId: String, title: String): JoynCataloguePage =
        categoryApi.loadCategory(blockId, title)

    suspend fun loadChannel(path: String, title: String): JoynCataloguePage =
        browseApi.loadChannel(path, title)

    suspend fun loadCollection(path: String, title: String): JoynCataloguePage =
        collectionApi.loadCollection(path, title)

    suspend fun loadCompilation(path: String, title: String): JoynCataloguePage =
        browseApi.loadCompilation(path, title)

    suspend fun searchMedia(text: String): List<JoynMediaItem> = api.searchMedia(text)
    suspend fun loadSeriesDetails(item: JoynMediaItem): JoynSeriesDetails = api.loadSeriesDetails(item)
    suspend fun loadSeasonEpisodes(seasonId: String): List<JoynMediaItem> = api.loadSeasonEpisodes(seasonId)
    suspend fun resolveLivePlayback(channelId: String): JoynPlayback = api.resolveLivePlayback(channelId)

    suspend fun resolveVodPlayback(contentRef: String, enteredPin: String? = null): JoynPlayback {
        val explicitPin = enteredPin?.takeIf { it.matches(Regex("^\\d{4}$")) }
        if (explicitPin != null) {
            if (!api.accountState(refreshRemote = false).loggedIn) throw JoynLoginRequiredException()
            return pinPlaybackApi.resolveVodPlayback(contentRef, explicitPin)
        }
        return try {
            api.resolveVodPlayback(contentRef)
        } catch (error: Throwable) {
            val details = error.message.orEmpty()
            if (
                details.contains("ENT_AgeVerificationSetupRequired", ignoreCase = true) &&
                !api.accountState(refreshRemote = false).loggedIn
            ) {
                throw JoynLoginRequiredException()
            }
            if (!details.contains("ENT_PINRequired", ignoreCase = true)) throw error
            if (!api.accountState(refreshRemote = false).loggedIn) throw JoynLoginRequiredException()
            val automaticPin = if (pinSettings.autoUse()) pinSettings.readPin() else null
            if (automaticPin != null) pinPlaybackApi.resolveVodPlayback(contentRef, automaticPin)
            else throw JoynPinRequiredException()
        }
    }

    fun hasStoredParentalPin(): Boolean = pinSettings.hasPin()
    fun parentalPinAutoUse(): Boolean = pinSettings.autoUse()
    fun saveParentalPin(pin: String, autoUse: Boolean = true) = pinSettings.save(pin, autoUse)
    fun setParentalPinAutoUse(enabled: Boolean) = pinSettings.setAutoUse(enabled)
    fun clearParentalPin() = pinSettings.clear()

    suspend fun login(email: String, password: String): JoynAccountState = api.login(email, password)
    suspend fun logout() = api.logout()
    suspend fun accountState(refreshRemote: Boolean = true): JoynAccountState = api.accountState(refreshRemote)

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

    fun disableProxy() {
        proxySettings.disable()
    }

    fun mysteriumCountrySettings(country: JoynCountry = currentCountry()): JoynMysteriumCountrySettings =
        mysteriumSettings.countrySettings(country)

    fun mysteriumSavedEmail(): String = mysteriumApiClient.savedEmail()
    fun mysteriumHasStoredAccessToken(): Boolean = mysteriumApiClient.hasStoredAccessToken()
    fun mysteriumVpnPermissionIntent(activity: Activity): Intent? = JoynMysteriumWireGuard.permissionIntent(activity)

    suspend fun mysteriumVpnConnected(): Boolean = JoynMysteriumWireGuard.isConnected(appContext)

    suspend fun disconnectMysteriumVpn(): Result<Unit> = JoynMysteriumWireGuard.disconnect(appContext)

    fun saveMysteriumSettings(
        country: JoynCountry,
        maxAttempts: Int,
        allTraffic: Boolean = mysteriumSettings.countrySettings(country).allTraffic,
    ) {
        mysteriumSettings.saveCountrySettings(
            JoynMysteriumCountrySettings(
                country = country,
                maxAttempts = maxAttempts,
                allTraffic = allTraffic,
            ),
        )
    }

    suspend fun mysteriumApiStatus(): JoynMysteriumApiStatus =
        withContext(Dispatchers.IO) { mysteriumApiClient.status() }

    suspend fun requestMysteriumMagicLink(email: String): Result<JoynMysteriumMagicLinkResult> =
        withContext(Dispatchers.IO) { mysteriumApiClient.requestMagicLink(email) }

    suspend fun completeMysteriumMagicLink(codeOrLink: String): Result<Unit> =
        withContext(Dispatchers.IO) { mysteriumApiClient.completeMagicLink(codeOrLink) }

    fun saveMysteriumAccessToken(token: String) = mysteriumApiClient.saveManualAccessToken(token)
    fun logoutMysterium() = mysteriumApiClient.clearSession()

    suspend fun findMysteriumResidentialProxy(
        country: JoynCountry,
        maxAttempts: Int,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        saveMysteriumSettings(country, maxAttempts, allTraffic)
        val progressRelay = progressRelay(onProgress)

        // The old connect-proxy transport and WireGuard must never be active at the same time.
        // Persistently disable it before asking Android to route this APK through WireGuard.
        proxySettings.disable()
        mysteriumSettings.clearLastSuccessful(country)

        val accountStatus = withContext(Dispatchers.IO) { mysteriumApiClient.status() }
        if (accountStatus.authenticated != true) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = maxAttempts,
                attempted = 0,
                message = accountStatus.message + "\nBitte zuerst im Mysterium-Bereich anmelden.",
            )
        }

        return withContext(Dispatchers.IO) {
            mysteriumScanner.findBest(
                country = country,
                apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                maxAttempts = maxAttempts,
                allTraffic = allTraffic,
                onProgress = progressRelay,
            )
        }
    }

    fun stopMysteriumResidentialScan() {
        JoynMysteriumScanControl.requestStop()
    }

    fun mysteriumLastSuccessful(country: JoynCountry = currentCountry()): JoynProxyConfig? =
        mysteriumSettings.lastSuccessful(country)

    fun mysteriumLeaseNeedsRefresh(country: JoynCountry = currentCountry()): Boolean =
        mysteriumSettings.leaseNeedsRefresh(country)

    private fun progressRelay(
        onProgress: (JoynProxyDiscoveryProgress) -> Unit,
    ): (JoynProxyDiscoveryProgress) -> Unit {
        val mainHandler = Handler(Looper.getMainLooper())
        return { progress ->
            if (Looper.myLooper() == Looper.getMainLooper()) onProgress(progress)
            else mainHandler.post { onProgress(progress) }
        }
    }
}

internal class JoynLoginRequiredException : Exception(
    "Für diesen geschützten Inhalt musst du in Joyn TV mit deinem Joyn-Konto angemeldet sein.",
)
