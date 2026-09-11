package com.andreassamitsch.joyntv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mysteriumWireGuardApi = JoynMysteriumWireGuardApiClient(appContext, mysteriumApiClient)
    private val mysteriumScanner = JoynMysteriumProxyScanner(appContext, mysteriumApiClient)
    private val pinSettings = JoynParentalPinSettings(appContext)
    private val api = JoynApiClient(appContext)
    private val pinPlaybackApi = JoynPinPlaybackApiClient(appContext)
    private val browseApi = JoynBrowseApiClient(appContext)
    private val categoryApi = JoynCategoryApiClient(appContext)
    private val collectionApi = JoynCollectionApiClient(appContext)
    private val previewPublisher = JoynPreviewChannelPublisher(appContext)
    private val countryRoutingMutex = Mutex()

    suspend fun loadLiveChannelsAndPublish(): List<JoynLiveChannel> {
        ensureJoynCountryRouting()
        val channels = api.loadLiveChannels()
        previewPublisher.publishLive(channels)
        return channels
    }

    suspend fun loadCatalogue(path: String = "/neu-beliebt"): JoynCataloguePage {
        ensureJoynCountryRouting()
        val page = api.loadCatalogue(path)
        if (path != "/neu-beliebt") return page
        val browseLanes = buildList {
            runCatching { browseApi.loadMediaLibraries() }.getOrNull()?.lanes?.let(::addAll)
            runCatching { browseApi.loadCategories("/") }.getOrNull()?.lanes?.let(::addAll)
        }
        return page.copy(lanes = browseLanes + page.lanes)
    }

    suspend fun loadCategory(blockId: String, title: String): JoynCataloguePage {
        ensureJoynCountryRouting()
        return categoryApi.loadCategory(blockId, title)
    }

    suspend fun loadChannel(path: String, title: String): JoynCataloguePage {
        ensureJoynCountryRouting()
        return browseApi.loadChannel(path, title)
    }

    suspend fun loadCollection(path: String, title: String): JoynCataloguePage {
        ensureJoynCountryRouting()
        return collectionApi.loadCollection(path, title)
    }

    suspend fun loadCompilation(path: String, title: String): JoynCataloguePage {
        ensureJoynCountryRouting()
        return browseApi.loadCompilation(path, title)
    }

    suspend fun searchMedia(text: String): List<JoynMediaItem> {
        ensureJoynCountryRouting()
        return api.searchMedia(text)
    }

    suspend fun loadSeriesDetails(item: JoynMediaItem): JoynSeriesDetails {
        ensureJoynCountryRouting()
        return api.loadSeriesDetails(item)
    }

    suspend fun loadSeasonEpisodes(seasonId: String): List<JoynMediaItem> {
        ensureJoynCountryRouting()
        return api.loadSeasonEpisodes(seasonId)
    }

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback {
        ensureJoynCountryRouting()
        return api.resolveLivePlayback(channelId)
    }

    suspend fun resolveVodPlayback(contentRef: String, enteredPin: String? = null): JoynPlayback {
        ensureJoynCountryRouting()
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

    suspend fun login(email: String, password: String): JoynAccountState {
        ensureJoynCountryRouting()
        return api.login(email, password)
    }

    suspend fun logout() {
        ensureJoynCountryRouting()
        api.logout()
    }

    suspend fun accountState(refreshRemote: Boolean = true): JoynAccountState {
        ensureJoynCountryRouting()
        return api.accountState(refreshRemote)
    }

    fun currentCountry(): JoynCountry = regionSettings.currentCountry()
    fun selectedCountry(): JoynCountry? = regionSettings.selectedCountry()
    fun countryIsAutomatic(): Boolean = regionSettings.isAutomatic()
    fun setCountry(country: JoynCountry?) {
        regionSettings.setCountry(country)
    }

    fun proxyConfig(): JoynProxyConfig = proxySettings.current()
    fun setProxy(config: JoynProxyConfig) {
        proxySettings.save(config)
        if (!config.enabled) {
            // "Direkt verwenden" is country-specific now. The next Joyn request will tear down an
            // already running WireGuard tunnel before any direct traffic is sent.
            mysteriumSettings.setWireGuardEnabled(currentCountry(), false)
        }
    }

    fun disableProxy() {
        proxySettings.disable()
        mysteriumSettings.setWireGuardEnabled(currentCountry(), false)
    }

    fun mysteriumCountrySettings(country: JoynCountry = currentCountry()): JoynMysteriumCountrySettings =
        mysteriumSettings.countrySettings(country)

    fun mysteriumWireGuardProfile(country: JoynCountry = currentCountry()): JoynMysteriumWireGuardProfile? =
        mysteriumSettings.wireGuardProfile(country)

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

    /**
     * Ensures the saved Mysterium profile for the currently selected Joyn market is active.
     *
     * Each country remembers the exact Residential IP which already passed Joyn. When the user
     * switches markets or the app process restarts, target_ip asks Mysterium to recreate that same
     * approved exit instead of running the expensive scanner again.
     */
    suspend fun ensureMysteriumForCurrentCountry(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            countryRoutingMutex.withLock {
                val country = currentCountry()
                val profile = mysteriumSettings.wireGuardProfile(country)

                if (profile == null || !profile.enabled) {
                    if (JoynMysteriumWireGuard.isConnected(appContext)) {
                        JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
                    }
                    return@withLock
                }

                if (
                    JoynMysteriumWireGuard.activeCountry() == country &&
                    JoynMysteriumWireGuard.isConnected(appContext)
                ) {
                    return@withLock
                }

                // A WireGuard public key represents one prepared Mysterium connection. Always tear
                // down the old local market first, then recreate the remembered exit server-side.
                JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
                val publicKey = JoynMysteriumWireGuard.publicKey(appContext)
                val lease = mysteriumWireGuardApi.requestResidentialTarget(
                    country = country,
                    publicKey = publicKey,
                    targetIp = profile.exitIp,
                ).getOrThrow()

                JoynMysteriumWireGuard.connect(
                    context = appContext,
                    country = country,
                    configTemplate = lease.config,
                ).getOrThrow()
                mysteriumSettings.saveWireGuardProfile(country, lease)
            }
        }
    }

    suspend fun findMysteriumResidentialProxy(
        country: JoynCountry,
        maxAttempts: Int,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        saveMysteriumSettings(country, maxAttempts, allTraffic)
        val progressRelay = progressRelay(onProgress)

        // Disable only the obsolete HTTP proxy. A previously accepted WireGuard profile remains
        // stored until a new candidate has actually passed Joyn, so a failed rescan cannot destroy
        // the known-good country configuration.
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

        val result = withContext(Dispatchers.IO) {
            mysteriumScanner.findBest(
                country = country,
                apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                maxAttempts = maxAttempts,
                allTraffic = allTraffic,
                onProgress = progressRelay,
            )
        }

        if (result.activated) {
            // requestResidential() saved the last candidate; because the scanner leaves only the
            // successful tunnel UP, this candidate is exactly the Joyn-approved exit.
            mysteriumSettings.promoteWireGuardCandidate(country)
            JoynMysteriumWireGuard.adoptActiveCountry(country)
        }
        return result
    }

    fun stopMysteriumResidentialScan() {
        JoynMysteriumScanControl.requestStop()
    }

    fun mysteriumLastSuccessful(country: JoynCountry = currentCountry()): JoynProxyConfig? =
        mysteriumSettings.lastSuccessful(country)

    fun mysteriumLeaseNeedsRefresh(country: JoynCountry = currentCountry()): Boolean =
        mysteriumSettings.leaseNeedsRefresh(country)

    private suspend fun ensureJoynCountryRouting() {
        ensureMysteriumForCurrentCountry().getOrElse { error ->
            throw JoynMysteriumRoutingException(
                "Mysterium ${currentCountry().name} konnte nicht wiederhergestellt werden: " +
                    (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
    }

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

internal class JoynMysteriumRoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class JoynLoginRequiredException : Exception(
    "Für diesen geschützten Inhalt musst du in Joyn TV mit deinem Joyn-Konto angemeldet sein.",
)
