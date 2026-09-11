package com.andreassamitsch.joyntv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private val multiCountryLiveApi = JoynMultiCountryLiveApiClient(appContext)
    private val pinPlaybackApi = JoynPinPlaybackApiClient(appContext)
    private val browseApi = JoynBrowseApiClient(appContext)
    private val categoryApi = JoynCategoryApiClient(appContext)
    private val collectionApi = JoynCollectionApiClient(appContext)
    private val previewPublisher = JoynPreviewChannelPublisher(appContext)
    private val networkOperationMutex = Mutex()

    suspend fun loadLiveChannelsAndPublish(): List<JoynLiveChannel> = networkOperationMutex.withLock {
        val selected = currentCountry()
        val loadOrder = LIVE_COUNTRIES.filter { it != selected } + selected
        val byCountry = mutableMapOf<JoynCountry, List<JoynLiveChannel>>()
        val failures = mutableListOf<String>()

        loadOrder.forEach { country ->
            runCatching {
                ensureJoynCountryRouting(country)
                multiCountryLiveApi.loadLiveChannels(country)
            }.onSuccess { channels ->
                byCountry[country] = channels
            }.onFailure { error ->
                failures += "${country.name}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        val channels = JoynLiveChannelOrder.sort(
            LIVE_COUNTRIES.flatMap { country ->
                byCountry[country].orEmpty().map { channel -> country to channel }
            },
        ).map { (country, channel) -> decorateLiveChannel(country, channel) }
        if (channels.isEmpty()) {
            error(
                "Live TV konnte für AT, DE und CH nicht geladen werden" +
                    failures.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ", separator = " · ").orEmpty(),
            )
        }
        if (failures.isNotEmpty()) {
            Log.w(TAG, "Einzelne Live-TV-Länder konnten nicht geladen werden: ${failures.joinToString(" · ")}")
        }

        previewPublisher.publishLive(channels)
        channels
    }

    suspend fun loadCatalogue(path: String = "/neu-beliebt"): JoynCataloguePage =
        networkOperationMutex.withLock {
            ensureJoynCountryRouting()
            val page = api.loadCatalogue(path)
            if (path != "/neu-beliebt") return@withLock page
            val browseLanes = buildList {
                runCatching { browseApi.loadMediaLibraries() }.getOrNull()?.lanes?.let(::addAll)
                runCatching { browseApi.loadCategories("/") }.getOrNull()?.lanes?.let(::addAll)
            }
            page.copy(lanes = browseLanes + page.lanes)
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

    suspend fun prepareLiveChannel(channelId: String): Result<Unit> {
        val countryRef = parseLiveChannelRef(channelId) ?: return Result.success(Unit)
        return ensureMysteriumForCountry(countryRef.country)
    }

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback = networkOperationMutex.withLock {
        val countryRef = parseLiveChannelRef(channelId)
        if (countryRef == null) {
            ensureJoynCountryRouting()
            return@withLock api.resolveLivePlayback(channelId)
        }

        val startedAt = SystemClock.elapsedRealtime()
        ensureJoynCountryRouting(countryRef.country)
        val routingMs = SystemClock.elapsedRealtime() - startedAt
        val playback = multiCountryLiveApi.resolveLivePlayback(countryRef.country, countryRef.channelId)
        Log.i(
            TAG,
            "Live startup ${countryRef.country.name}: routing=${routingMs}ms, total=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        playback
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
        val account = api.login(email, password)
        regionSettings.persistActiveSession(currentCountry())
        return account
    }

    suspend fun logout() {
        ensureJoynCountryRouting()
        val country = currentCountry()
        api.logout()
        regionSettings.clearSession(country)
    }

    suspend fun accountState(refreshRemote: Boolean = true): JoynAccountState {
        ensureJoynCountryRouting()
        val account = api.accountState(refreshRemote)
        if (account.loggedIn) regionSettings.persistActiveSession(currentCountry())
        return account
    }

    fun hasStoredJoynAccountSession(country: JoynCountry): Boolean =
        multiCountryLiveApi.hasStoredAccountSession(country)

    fun currentCountry(): JoynCountry = regionSettings.currentCountry()
    fun selectedCountry(): JoynCountry? = regionSettings.selectedCountry()
    fun countryIsAutomatic(): Boolean = regionSettings.isAutomatic()
    fun setCountry(country: JoynCountry?) {
        regionSettings.setCountry(country)
    }

    fun proxyConfig(): JoynProxyConfig = proxySettings.current()
    fun setProxy(config: JoynProxyConfig) {
        proxySettings.save(config)
        ACTIVE_MYSTERIUM_PROXY_COUNTRY = if (config.isUsable && config.isMysterium) {
            JoynCountry.entries.firstOrNull { config.source.contains(it.name, ignoreCase = true) }
        } else {
            null
        }
        if (!config.enabled) {
            mysteriumSettings.setWireGuardEnabled(currentCountry(), false)
        }
    }

    fun disableProxy() {
        proxySettings.disable()
        ACTIVE_MYSTERIUM_PROXY_COUNTRY = null
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

    suspend fun disconnectMysteriumVpn(): Result<Unit> {
        ACTIVE_MYSTERIUM_PROXY_COUNTRY = null
        return JoynMysteriumWireGuard.disconnect(appContext)
    }

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
     * Restores the fastest valid Mysterium route for [country]. A verified connect-proxy lease is
     * preferred because it needs no Android VPN/TUN setup. If the lease has expired or is missing,
     * the previously verified app-scoped WireGuard profile remains the compatibility fallback.
     */
    suspend fun ensureMysteriumForCountry(country: JoynCountry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            GLOBAL_COUNTRY_ROUTING_MUTEX.withLock {
                val proxyProfile = mysteriumSettings.lastSuccessful(country)
                val proxyValid = proxyProfile != null && !mysteriumSettings.leaseNeedsRefresh(country)
                if (proxyValid) {
                    val desired = requireNotNull(proxyProfile).copy(
                        enabled = true,
                        automatic = true,
                        allTraffic = true,
                    )
                    if (
                        ACTIVE_MYSTERIUM_PROXY_COUNTRY == country &&
                        proxySettings.current().isUsable &&
                        proxySettings.current().host == desired.host &&
                        proxySettings.current().port == desired.port
                    ) {
                        return@withLock
                    }

                    val startedAt = SystemClock.elapsedRealtime()
                    if (JoynMysteriumWireGuard.isConnected(appContext)) {
                        JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
                    }
                    // save() updates the stable process ProxySelector already held by OkHttp and
                    // Media3, so the next manifest/DRM/segment request switches without rebuilding
                    // the player or creating an Android VPN interface.
                    proxySettings.save(desired)
                    ACTIVE_MYSTERIUM_PROXY_COUNTRY = country
                    multiCountryLiveApi.onRouteChanged()
                    Log.i(
                        TAG,
                        "Mysterium proxy switch ->${country.name} in ${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                    return@withLock
                }

                // No usable proxy lease for this market. Never let a previously selected country's
                // proxy bleed into the WireGuard/direct fallback path.
                if (ACTIVE_MYSTERIUM_PROXY_COUNTRY != null || proxySettings.current().isMysterium) {
                    JoynProxySettings.installDirectForTunnel()
                    ACTIVE_MYSTERIUM_PROXY_COUNTRY = null
                }

                val profile = mysteriumSettings.wireGuardProfile(country)
                if (profile == null || !profile.enabled) {
                    if (JoynMysteriumWireGuard.isConnected(appContext)) {
                        JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
                        multiCountryLiveApi.onRouteChanged()
                    }
                    return@withLock
                }

                if (
                    JoynMysteriumWireGuard.activeCountry() == country &&
                    JoynMysteriumWireGuard.isConnected(appContext)
                ) {
                    return@withLock
                }

                val switchStartedAt = SystemClock.elapsedRealtime()
                val previousCountry = JoynMysteriumWireGuard.activeCountry()
                val crossCountrySwitch = previousCountry != null && previousCountry != country
                val publicKey = JoynMysteriumWireGuard.publicKey(appContext, country)

                suspend fun activateTarget(
                    forceRefresh: Boolean,
                    verifyExit: Boolean,
                    timeoutMs: Long,
                ): JoynMysteriumWireGuardLease {
                    val apiStartedAt = SystemClock.elapsedRealtime()
                    val lease = mysteriumWireGuardApi.requestResidentialTarget(
                        country = country,
                        publicKey = publicKey,
                        targetIp = profile.exitIp,
                        forceRefresh = forceRefresh,
                    ).getOrThrow()
                    val apiMs = SystemClock.elapsedRealtime() - apiStartedAt

                    val wgStartedAt = SystemClock.elapsedRealtime()
                    JoynMysteriumWireGuard.connect(
                        context = appContext,
                        country = country,
                        configTemplate = lease.config,
                    ).getOrThrow()
                    val wgMs = SystemClock.elapsedRealtime() - wgStartedAt

                    var verifyMs = 0L
                    if (verifyExit) {
                        val verifyStartedAt = SystemClock.elapsedRealtime()
                        JoynMysteriumTunnelProbe.awaitReady(
                            context = appContext,
                            country = country,
                            expectedExitIp = profile.exitIp,
                            timeoutMs = timeoutMs,
                        ).getOrThrow()
                        verifyMs = SystemClock.elapsedRealtime() - verifyStartedAt
                    }
                    Log.i(
                        TAG,
                        "Mysterium WG phase ${previousCountry?.name ?: "DIRECT"}->${country.name}: " +
                            "api=${apiMs}ms wg=${wgMs}ms verify=${verifyMs}ms refresh=$forceRefresh",
                    )
                    return lease
                }

                val lease = if (crossCountrySwitch) {
                    activateTarget(
                        forceRefresh = true,
                        verifyExit = false,
                        timeoutMs = 0L,
                    )
                } else {
                    val firstAttempt = runCatching {
                        activateTarget(
                            forceRefresh = false,
                            verifyExit = true,
                            timeoutMs = FAST_TUNNEL_READY_TIMEOUT_MS,
                        )
                    }
                    firstAttempt.getOrElse { firstError ->
                        val details = firstError.message.orEmpty()
                        if (details.contains("429") || details.contains("Sitzung ist abgelaufen", ignoreCase = true)) {
                            throw firstError
                        }
                        if (JoynMysteriumWireGuard.activeCountry() == country) {
                            JoynMysteriumWireGuard.disconnect(appContext).getOrThrow()
                        }
                        activateTarget(
                            forceRefresh = true,
                            verifyExit = true,
                            timeoutMs = REFRESH_TUNNEL_READY_TIMEOUT_MS,
                        )
                    }
                }

                mysteriumSettings.saveWireGuardProfile(country, lease)
                multiCountryLiveApi.onRouteChanged()
                Log.i(
                    TAG,
                    "Mysterium WG switch ${previousCountry?.name ?: "DIRECT"}->${country.name} in " +
                        "${SystemClock.elapsedRealtime() - switchStartedAt}ms",
                )
            }
        }.onFailure {
            if (JoynMysteriumWireGuard.activeCountry() == country) {
                runCatching { JoynMysteriumWireGuard.disconnect(appContext) }
            }
        }
    }

    suspend fun ensureMysteriumForCurrentCountry(): Result<Unit> =
        ensureMysteriumForCountry(currentCountry())

    suspend fun findMysteriumResidentialProxy(
        country: JoynCountry,
        maxAttempts: Int,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        saveMysteriumSettings(country, maxAttempts, allTraffic = true)
        val progressRelay = progressRelay(onProgress)

        proxySettings.disable()
        ACTIVE_MYSTERIUM_PROXY_COUNTRY = null
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
                allTraffic = true,
                onProgress = progressRelay,
            )
        }

        result.config?.let { config ->
            val active = config.copy(enabled = true, automatic = true, allTraffic = true)
            mysteriumSettings.saveLastSuccessful(country, active, result.expiresAt)
            if (JoynMysteriumWireGuard.isConnected(appContext)) {
                JoynMysteriumWireGuard.disconnect(appContext)
            }
            proxySettings.save(active)
            ACTIVE_MYSTERIUM_PROXY_COUNTRY = country
            multiCountryLiveApi.onRouteChanged()
        }

        // Compatibility for an older scanner result. The current proxy scanner leaves activated=false.
        if (result.activated) {
            mysteriumSettings.promoteWireGuardCandidate(country)
            JoynMysteriumWireGuard.adoptActiveCountry(country)
            ACTIVE_MYSTERIUM_PROXY_COUNTRY = null
            multiCountryLiveApi.onRouteChanged()
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

    private suspend fun ensureJoynCountryRouting(country: JoynCountry = currentCountry()) {
        ensureMysteriumForCountry(country).getOrElse { error ->
            throw JoynMysteriumRoutingException(
                "Mysterium ${country.name} konnte nicht wiederhergestellt werden: " +
                    (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
    }

    private fun decorateLiveChannel(country: JoynCountry, channel: JoynLiveChannel): JoynLiveChannel =
        channel.copy(
            id = "${MULTI_LIVE_PREFIX}${country.name}:${channel.id}",
            title = "${channel.title} · ${country.name}",
        )

    private fun parseLiveChannelRef(value: String): CountryLiveRef? {
        if (!value.startsWith(MULTI_LIVE_PREFIX)) return null
        val payload = value.removePrefix(MULTI_LIVE_PREFIX)
        val separator = payload.indexOf(':')
        if (separator <= 0 || separator == payload.lastIndex) return null
        val country = runCatching { JoynCountry.valueOf(payload.substring(0, separator)) }.getOrNull() ?: return null
        val channelId = payload.substring(separator + 1)
        if (channelId.isBlank()) return null
        return CountryLiveRef(country, channelId)
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

    private data class CountryLiveRef(val country: JoynCountry, val channelId: String)

    companion object {
        private const val TAG = "JoynRepository"
        private const val MULTI_LIVE_PREFIX = "multi:"
        private const val FAST_TUNNEL_READY_TIMEOUT_MS = 2_500L
        private const val REFRESH_TUNNEL_READY_TIMEOUT_MS = 4_000L
        private val GLOBAL_COUNTRY_ROUTING_MUTEX = Mutex()
        private val LIVE_COUNTRIES = listOf(JoynCountry.AT, JoynCountry.DE, JoynCountry.CH)

        @Volatile
        private var ACTIVE_MYSTERIUM_PROXY_COUNTRY: JoynCountry? = null
    }
}

internal class JoynMysteriumRoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class JoynLoginRequiredException : Exception(
    "Für diesen geschützten Inhalt musst du in Joyn TV mit deinem Joyn-Konto angemeldet sein.",
)

internal class JoynPinRequiredException : Exception(
    "Dieser Inhalt ist jugendgeschützt. Bitte Jugendschutz-PIN eingeben.",
)

internal class JoynPinInvalidException : Exception(
    "Der eingegebene Jugendschutz-PIN ist nicht korrekt.",
)
