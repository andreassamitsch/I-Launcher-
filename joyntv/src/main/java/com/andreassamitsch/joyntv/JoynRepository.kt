package com.andreassamitsch.joyntv

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
    }
    private val protocolPrefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val publicProxyResolver = JoynPublicProxyResolver()
    private val nordVpnProxyResolver = JoynNordVpnProxyResolver()
    private val nordVpnDiagnostics = JoynNordVpnDiagnostics()
    private val nordCredentialDiagnostics = JoynNordCredentialDiagnostics()
    private val nordHttpsCredentialDiagnostics = JoynNordHttpsCredentialDiagnostics()
    private val nordOpenVpnScanner = JoynNordOpenVpnScanner(appContext)
    private val mysteriumSettings = JoynMysteriumSettings(appContext)
    private val mysteriumApiClient = JoynMysteriumApiClient(appContext)
    private val mysteriumScanner = JoynMysteriumProxyScanner(mysteriumApiClient)
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

    suspend fun loadCategory(blockId: String, title: String): JoynCataloguePage = categoryApi.loadCategory(blockId, title)
    suspend fun loadChannel(path: String, title: String): JoynCataloguePage = browseApi.loadChannel(path, title)
    suspend fun loadCollection(path: String, title: String): JoynCataloguePage = collectionApi.loadCollection(path, title)
    suspend fun loadCompilation(path: String, title: String): JoynCataloguePage = browseApi.loadCompilation(path, title)
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
            if (details.contains("ENT_AgeVerificationSetupRequired", ignoreCase = true) &&
                !api.accountState(refreshRemote = false).loggedIn
            ) throw JoynLoginRequiredException()
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
    fun setCountry(country: JoynCountry?) { regionSettings.setCountry(country) }
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

    fun mysteriumApiBaseUrl(): String = mysteriumSettings.apiBaseUrl()
    fun mysteriumCountrySettings(country: JoynCountry = currentCountry()): JoynMysteriumCountrySettings =
        mysteriumSettings.countrySettings(country)
    fun mysteriumSavedEmail(): String = mysteriumApiClient.savedEmail()
    fun mysteriumHasStoredAccessToken(): Boolean = mysteriumApiClient.hasStoredAccessToken()

    fun saveMysteriumSettings(apiBaseUrl: String, country: JoynCountry, maxAttempts: Int) {
        mysteriumSettings.saveApiBaseUrl(apiBaseUrl)
        mysteriumSettings.saveCountrySettings(JoynMysteriumCountrySettings(country, maxAttempts))
    }

    suspend fun mysteriumApiStatus(apiBaseUrl: String = mysteriumSettings.apiBaseUrl()): JoynMysteriumApiStatus =
        withContext(Dispatchers.IO) { mysteriumApiClient.status(apiBaseUrl) }

    suspend fun requestMysteriumMagicLink(
        email: String,
        apiBaseUrl: String = mysteriumSettings.apiBaseUrl(),
    ): Result<JoynMysteriumMagicLinkResult> = withContext(Dispatchers.IO) {
        mysteriumSettings.saveApiBaseUrl(apiBaseUrl)
        mysteriumApiClient.requestMagicLink(apiBaseUrl, email)
    }

    suspend fun completeMysteriumMagicLink(
        codeOrLink: String,
        apiBaseUrl: String = mysteriumSettings.apiBaseUrl(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mysteriumApiClient.completeMagicLink(apiBaseUrl, codeOrLink)
    }

    fun saveMysteriumAccessToken(token: String) = mysteriumApiClient.saveManualAccessToken(token)
    fun logoutMysterium() = mysteriumApiClient.clearSession()

    suspend fun findMysteriumResidentialProxy(
        country: JoynCountry,
        apiBaseUrl: String,
        maxAttempts: Int,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        saveMysteriumSettings(apiBaseUrl, country, maxAttempts)
        val progressRelay = progressRelay(onProgress)
        val accountStatus = withContext(Dispatchers.IO) { mysteriumApiClient.status(apiBaseUrl) }
        if (accountStatus.authenticated != true) {
            withContext(Dispatchers.Main) {
                appContext.startActivity(
                    Intent(appContext, MysteriumSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = maxAttempts,
                attempted = 0,
                message = accountStatus.message + "\nMysterium-Konto/Länderprofile wurden geöffnet.",
            )
        }
        val result = withContext(Dispatchers.IO) {
            mysteriumScanner.findBest(
                country = country,
                apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                apiBaseUrl = accountStatus.resolvedBaseUrl.ifBlank { apiBaseUrl },
                maxAttempts = maxAttempts,
                allTraffic = allTraffic,
                onProgress = progressRelay,
            )
        }
        result.config?.let { mysteriumSettings.saveLastSuccessful(country, it) }
        return result
    }

    fun stopMysteriumResidentialScan() { JoynMysteriumScanControl.requestStop() }
    fun mysteriumLastSuccessful(country: JoynCountry = currentCountry(), allTraffic: Boolean = false): JoynProxyConfig? =
        mysteriumSettings.lastSuccessful(country, allTraffic)

    fun nordVpnServiceUsername(): String = protocolPrefs.getString(KEY_NORD_USERNAME, "").orEmpty()
    fun nordVpnServicePassword(): String = protocolPrefs.getString(KEY_NORD_PASSWORD, "").orEmpty()
    fun saveNordVpnServiceCredentials(username: String, password: String) {
        protocolPrefs.edit().putString(KEY_NORD_USERNAME, username.trim()).putString(KEY_NORD_PASSWORD, password).apply()
    }

    suspend fun findNordVpnProxy(
        username: String,
        password: String,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        val country = currentCountry()
        val progressRelay = progressRelay(onProgress)
        return try {
            progressRelay(JoynProxyDiscoveryProgress("Prüfe NordVPN HTTPS/89-Service-Credentials …"))
            val httpsCredentialDiagnostic = withContext(Dispatchers.IO) {
                nordHttpsCredentialDiagnostics.run(country, username, password)
            }
            if (httpsCredentialDiagnostic.status == JoynNordHttpsCredentialStatus.REJECTED) {
                return JoynProxyDiscoveryResult(null, httpsCredentialDiagnostic.attempted, httpsCredentialDiagnostic.attempted, httpsCredentialDiagnostic.summary)
            }
            val diagnostic = runCatching { nordVpnDiagnostics.run(country, progressRelay) }.getOrElse { error ->
                JoynNordVpnDiagnosticReport("Diagnose selbst fehlgeschlagen: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
            progressRelay(JoynProxyDiscoveryProgress("Prüfe NordVPN-Service-Credentials zusätzlich über offiziellen SOCKS5-Dienst …"))
            val credentialDiagnostic = withContext(Dispatchers.IO) { nordCredentialDiagnostics.run(username, password) }
            val result = withContext(Dispatchers.IO) {
                nordVpnProxyResolver.findBest(
                    country = country,
                    allTraffic = allTraffic,
                    apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                    username = username,
                    password = password,
                    onProgress = progressRelay,
                )
            }
            if (result.config == null) result.copy(
                message = result.message +
                    "\n\nNordVPN HTTPS/89-Credential-Debug:\n" + httpsCredentialDiagnostic.summary +
                    "\n\nNordVPN SOCKS5-Credential-Debug (nur Zusatztest):\n" + credentialDiagnostic.summary +
                    "\n\nNordVPN API-Debug:\n" + diagnostic.summary,
            ) else result
        } finally {
            JoynProxySettings.install(appContext)
        }
    }

    suspend fun findNordVpnOpenVpnTunnel(
        username: String,
        password: String,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynNordTunnelDiscoveryResult {
        val country = currentCountry()
        val progressRelay = progressRelay(onProgress)
        val previousProxy = proxySettings.current()
        JoynProxySettings.installDirectForTunnel()
        return try {
            val result = withContext(Dispatchers.IO) {
                nordOpenVpnScanner.findBest(
                    country = country,
                    apiKey = protocolPrefs.getString("api_key_${country.name}", null),
                    username = username,
                    password = password,
                    onProgress = progressRelay,
                )
            }
            if (result.connected) proxySettings.save(previousProxy.copy(enabled = false))
            else JoynProxySettings.install(appContext)
            result
        } catch (error: Throwable) {
            JoynProxySettings.install(appContext)
            throw error
        }
    }

    suspend fun disconnectNordVpnOpenVpnTunnel() {
        withContext(Dispatchers.IO) { nordOpenVpnScanner.disconnect() }
        JoynProxySettings.install(appContext)
    }

    fun nordVpnOpenVpnState(): JoynNordTunnelState = JoynNordTunnelRuntime.state.value
    fun setProxy(config: JoynProxyConfig) { proxySettings.save(config) }

    private fun progressRelay(onProgress: (JoynProxyDiscoveryProgress) -> Unit): (JoynProxyDiscoveryProgress) -> Unit {
        val mainHandler = Handler(Looper.getMainLooper())
        return { progress ->
            if (Looper.myLooper() == Looper.getMainLooper()) onProgress(progress)
            else mainHandler.post { onProgress(progress) }
        }
    }

    private companion object {
        private const val KEY_NORD_USERNAME = "nordvpn_service_username"
        private const val KEY_NORD_PASSWORD = "nordvpn_service_password"
    }
}

internal class JoynLoginRequiredException : Exception(
    "Für diesen geschützten Inhalt musst du in Joyn TV mit deinem Joyn-Konto angemeldet sein.",
)
