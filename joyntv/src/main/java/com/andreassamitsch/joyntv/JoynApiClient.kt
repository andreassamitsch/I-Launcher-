package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class JoynApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val tokenMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun loadLiveChannels(): List<JoynLiveChannel> {
        val config = bootstrapConfig()
        return runCatching { loadLiveChannelsOnce(config) }
            .recoverCatching { error ->
                if (error.shouldRefreshSession()) {
                    forceRefreshAuthorization(config.country)
                    loadLiveChannelsOnce(config)
                } else throw error
            }
            .getOrThrow()
    }

    suspend fun loadCatalogue(path: String = "/neu-beliebt"): JoynCataloguePage {
        val config = bootstrapConfig()
        val landing = persistedGraphQl(
            config = config,
            operationName = "LandingPageClient",
            hash = HASH_LANDING_PAGE,
            variables = JSONObject().put("path", path),
        )
        val page = landing.optJSONObject("page")
            ?: error("Joyn Mediathek: Seite '$path' wurde nicht gefunden")
        val blocks = mutableListOf<JSONObject>()
        page.optJSONArray("blocks").appendObjectsTo(blocks)
        page.optJSONArray("lazyBlocks").appendObjectsTo(blocks)

        val unresolvedIds = blocks
            .filter { it.optJSONArray("assets")?.length() ?: 0 == 0 }
            .mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .distinct()

        val resolvedById = mutableMapOf<String, JSONObject>()
        if (unresolvedIds.isNotEmpty()) {
            val ids = JSONArray().apply { unresolvedIds.forEach(::put) }
            val lazy = persistedGraphQl(
                config = config,
                operationName = "LandingBlocks",
                hash = HASH_LANDING_BLOCKS,
                variables = JSONObject().put("ids", ids),
            )
            lazy.optJSONArray("blocks")?.let { array ->
                for (index in 0 until array.length()) {
                    val block = array.optJSONObject(index) ?: continue
                    block.optString("id").takeIf(String::isNotBlank)?.let { resolvedById[it] = block }
                }
            }
        }

        val account = accountState(refreshRemote = false)
        val lanes = blocks.mapNotNull { original ->
            val id = original.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val block = resolvedById[id] ?: original
            val assets = block.optJSONArray("assets") ?: return@mapNotNull null
            val items = assets.toMediaItems()
                .filter { it.isFree || account.hasPlus }
                .distinctBy { it.id }
                .take(30)
            if (items.isEmpty()) return@mapNotNull null
            JoynLane(
                id = id,
                title = block.optString("headline").takeIf(String::isNotBlank)
                    ?: block.optString("title").takeIf(String::isNotBlank)
                    ?: "Joyn",
                items = items,
            )
        }.distinctBy { it.id }.take(14)

        return JoynCataloguePage(
            title = page.optString("title").takeIf(String::isNotBlank) ?: catalogueTitle(path),
            lanes = lanes,
        )
    }

    suspend fun searchMedia(text: String): List<JoynMediaItem> {
        if (text.isBlank()) return emptyList()
        val config = bootstrapConfig()
        val response = persistedGraphQl(
            config = config,
            operationName = "SearchQ",
            hash = HASH_SEARCH,
            variables = JSONObject()
                .put("text", text.trim())
                .put("first", 48)
                .put("offset", 0),
        )
        val account = accountState(refreshRemote = false)
        return response.optJSONObject("search")
            ?.optJSONArray("results")
            .toMediaItems()
            .filter { it.isFree || account.hasPlus }
            .distinctBy { it.id }
    }

    suspend fun loadSeriesDetails(item: JoynMediaItem): JoynSeriesDetails {
        val path = item.path ?: error("Für '${item.title}' fehlt der Joyn-Pfad")
        val config = bootstrapConfig()
        val response = persistedGraphQl(
            config = config,
            operationName = "SeriesDetailNewPageStatic",
            hash = HASH_SEASONS,
            variables = JSONObject()
                .put("path", path)
                .put("licenseFilter", "ALL"),
        )
        val seriesJson = response.optJSONObject("page")?.optJSONObject("series")
            ?: error("Seriendetails konnten nicht geladen werden")
        val mappedSeries = seriesJson.toMediaItem() ?: item
        val account = accountState(refreshRemote = false)
        val seasons = buildList {
            val array = seriesJson.optJSONArray("allSeasons") ?: JSONArray()
            for (index in 0 until array.length()) {
                val season = array.optJSONObject(index) ?: continue
                val id = season.optString("id").takeIf(String::isNotBlank) ?: continue
                val licenseTypes = season.optJSONArray("licenseTypes").toStringSet()
                if ("SVOD" in licenseTypes && !account.hasPlus) continue
                add(JoynSeason(id, season.optInt("number", index + 1), licenseTypes))
            }
        }
        return JoynSeriesDetails(mappedSeries, seasons.sortedBy(JoynSeason::number))
    }

    suspend fun loadSeasonEpisodes(seasonId: String): List<JoynMediaItem> {
        val config = bootstrapConfig()
        val account = accountState(refreshRemote = false)
        val response = persistedGraphQl(
            config = config,
            operationName = "Season",
            hash = HASH_EPISODES,
            variables = JSONObject()
                .put("id", seasonId)
                .put("licenseFilter", "ALL")
                .put("first", 1000)
                .put("offset", 0),
        )
        return response.optJSONObject("season")
            ?.optJSONArray("episodes")
            .toMediaItems()
            .filter { it.isFree || account.hasPlus }
            .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
    }

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback =
        resolvePlaybackWithRecovery(channelId, "LIVE", "channel")

    suspend fun resolveVodPlayback(contentRef: String): JoynPlayback {
        val videoId = if (contentRef.startsWith('/')) resolveMovieVideoId(contentRef) else contentRef
        return resolvePlaybackWithRecovery(videoId, "VOD", "asset")
    }

    private suspend fun resolveMovieVideoId(path: String): String {
        val config = bootstrapConfig()
        val response = persistedGraphQl(
            config = config,
            operationName = "PageMovieDetailStatic",
            hash = HASH_MOVIE_DETAIL,
            variables = JSONObject().put("path", path),
        )
        val movie = response.optJSONObject("page")?.optJSONObject("movie")
            ?: error("Joyn Film '$path' wurde nicht gefunden")
        return movie.optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank)
            ?: error("Joyn Film '${movie.optString("title").ifBlank { path }}' enthält keine Video-ID")
    }

    suspend fun login(email: String, password: String): JoynAccountState {
        require(email.contains('@')) { "Bitte eine gültige E-Mail-Adresse eingeben" }
        require(password.length >= 6) { "Das Passwort ist zu kurz" }
        val config = bootstrapConfig()
        val cookieJar = MemoryCookieJar()
        val ssoClient = client.newBuilder().cookieJar(cookieJar).build()
        val authHeaders: Request.Builder.() -> Unit = {
            header("User-Agent", USER_AGENT)
            header("Joyn-Country", config.country.name)
            header("Joyn-Distribution-Tenant", config.country.authTenant)
        }

        val endpointsUrl = "https://auth.joyn.de/sso/endpoints".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId())
            .addQueryParameter("client_name", "web")
            .build()
        val endpoints = executeJson(
            Request.Builder().url(endpointsUrl).apply(authHeaders).get().build(),
            ssoClient,
        )
        val webLogin = endpoints.optString("web-login").takeIf(String::isNotBlank)
            ?: error("Joyn Login-Endpunkt fehlt")
        val redeemTokenUrl = endpoints.optString("redeem-token").takeIf(String::isNotBlank)
            ?: error("Joyn Token-Endpunkt fehlt")

        val loginLanding = executeResponse(
            Request.Builder().url(webLogin).apply(authHeaders).get().build(),
            ssoClient,
        )
        val requestId = loginLanding.request.url.queryParameter("requestId")
            ?: error("Joyn Login konnte keine requestId erzeugen")
        val sevenPassClientId = loginLanding.request.url.queryParameter("client_id")
            ?: webLogin.toHttpUrl().queryParameter("client_id")
            ?: error("Joyn Login konnte keine client_id ermitteln")

        executeJson(
            Request.Builder()
                .url("https://auth.7pass.de/registration-setup-srv/public/list?acceptlanguage=undefined&requestId=$requestId")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
            ssoClient,
        )
        postJson(
            ssoClient,
            "https://auth.7pass.de/users-srv/user/checkexists/$requestId",
            JSONObject().put("email", email.trim()).put("requestId", requestId),
        )
        postJson(
            ssoClient,
            "https://auth.7pass.de/verification-srv/v2/setup/public/configured/list",
            JSONObject().put("email", email.trim()).put("request_id", requestId),
        )

        var redirect = executeResponse(
            Request.Builder()
                .url("https://auth.7pass.de/login-srv/login")
                .header("User-Agent", USER_AGENT)
                .post(
                    FormBody.Builder()
                        .add("username", email.trim())
                        .add("password", password)
                        .add("requestId", requestId)
                        .build(),
                )
                .build(),
            ssoClient,
        ).request.url

        if (redirect.queryParameter("code").isNullOrBlank()) {
            val subject = redirect.queryParameter("sub")
                ?: error("Joyn Login wurde abgelehnt. E-Mail oder Passwort prüfen.")
            val trackId = redirect.queryParameter("track_id") ?: redirect.queryParameter("cd1")
                ?: error("Joyn Login-Tracking fehlt")
            postJson(
                ssoClient,
                "https://auth.7pass.de/consent-management-srv/consent/scope/accept",
                JSONObject()
                    .put("sub", subject)
                    .put("client_id", sevenPassClientId)
                    .put("scopes", JSONArray().put(JSONObject().put("offline_access", "denied"))),
            )
            redirect = executeResponse(
                Request.Builder()
                    .url("https://auth.7pass.de/login-srv/precheck/continue/$trackId")
                    .header("User-Agent", USER_AGENT)
                    .post(ByteArray(0).toRequestBody(null))
                    .build(),
                ssoClient,
            ).request.url
        }

        val code = redirect.queryParameter("code")
            ?: error("Joyn Login wurde nicht abgeschlossen")
        val trackingId = redirect.queryParameter("cd1")
            ?: redirect.queryParameter("track_id")
            ?: error("Joyn Login-Tracking konnte nicht abgeschlossen werden")
        val redeemPayload = JSONObject()
            .put("client_id", sevenPassClientId)
            .put("code", code)
            .put("code_verifier", "")
            .put("redirect_uri", "https://www.joyn.${config.country.webSuffix}/oauth")
            .put("tracking_id", trackingId)
            .put("tracking_name", "web")
        val tokenJson = executeJson(
            Request.Builder()
                .url(redeemTokenUrl)
                .apply(authHeaders)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(redeemPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            ssoClient,
        )
        val token = tokenJson.toStoredToken(hasAccount = true, email = email.trim())
        tokenMutex.withLock { storeToken(token) }
        return accountState(refreshRemote = true)
    }

    suspend fun logout() {
        tokenMutex.withLock {
            prefs.edit().remove("auth_token").apply()
            createAnonymousToken(country)
        }
    }

    suspend fun accountState(refreshRemote: Boolean = true): JoynAccountState {
        val local = readToken()
        if (local?.hasAccount != true) return JoynAccountState(loggedIn = false)
        if (!refreshRemote) {
            return JoynAccountState(loggedIn = true, email = local.email)
        }
        val config = bootstrapConfig()
        return runCatching {
            val response = persistedGraphQl(
                config = config,
                operationName = "GetMeState",
                hash = HASH_ACCOUNT,
                variables = JSONObject(),
            )
            val me = response.optJSONObject("me")
            val profile = me?.optJSONObject("profile")
            val subscriptions = me?.optJSONObject("subscriptionsData")?.optJSONObject("config")
            JoynAccountState(
                loggedIn = true,
                email = profile?.optString("email")?.takeIf(String::isNotBlank) ?: local.email,
                hasPlus = subscriptions?.optBoolean("hasActivePlus", false) ?: false,
                hasHd = subscriptions?.optBoolean("hasActiveHD", false) ?: false,
            )
        }.getOrElse {
            JoynAccountState(loggedIn = true, email = local.email)
        }
    }

    private suspend fun resolvePlaybackWithRecovery(
        contentId: String,
        contentType: String,
        pathType: String,
    ): JoynPlayback {
        val config = bootstrapConfig()
        var lastError: Throwable? = null
        repeat(MAX_PLAYBACK_ATTEMPTS) { attempt ->
            try {
                return resolvePlaybackOnce(contentId, contentType, pathType, config)
            } catch (error: Throwable) {
                lastError = error
                when {
                    error.requiresAnonymousReset() && readToken()?.hasAccount != true -> resetAnonymousAuthorization(config.country)
                    error.shouldRefreshSession() && attempt == 0 -> forceRefreshAuthorization(config.country)
                    error.shouldRefreshSession() && readToken()?.hasAccount != true -> resetAnonymousAuthorization(config.country)
                    else -> throw error
                }
            }
        }
        throw lastError ?: error("Joyn playback failed without an error")
    }

    private suspend fun resolvePlaybackOnce(
        contentId: String,
        contentType: String,
        pathType: String,
        config: JoynRuntimeConfig,
    ): JoynPlayback {
        val authorization = authorizationHeader(config.country)
        val entitlementBody = JSONObject()
            .put("content_id", contentId)
            .put("content_type", contentType)
            .toString()
        val entitlementJson = executeJson(
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", authorization)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(entitlementBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val entitlementToken = entitlementJson.optString("entitlement_token").takeIf(String::isNotBlank)
            ?: run {
                val body = entitlementJson.toString()
                if (body.contains("INVALID_JWT", true) || body.contains("ENT_VALIDATION_TOKEN_ERROR", true)) {
                    throw JoynSessionException(body)
                }
                if (body.contains("ENT_RVOD_Playback_Restricted", true)) {
                    throw JoynAnonymousSessionRestrictedException(body)
                }
                error("Joyn did not return an entitlement token: ${body.take(220)}")
            }
        val signature = JoynProtocol.playbackSignature(entitlementToken)
        val playlistUrl = "${JoynProtocol.playbackBaseUrl}/$pathType/$contentId/playlist"
            .toHttpUrl().newBuilder().addQueryParameter("signature", signature).build()
        val playlist = executeJson(
            Request.Builder()
                .url(playlistUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $entitlementToken")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(JoynProtocol.playerPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val manifest = playlist.optString("manifestUrl").takeIf(String::isNotBlank)
            ?: error("Joyn did not return a DASH manifest: ${playlist.toString().take(220)}")
        return JoynPlayback(
            manifestUrl = manifest,
            licenseUrl = playlist.optString("licenseUrl").takeIf(String::isNotBlank),
            certificateUrl = playlist.optString("certificateUrl").takeIf(String::isNotBlank),
        )
    }

    private suspend fun loadLiveChannelsOnce(config: JoynRuntimeConfig): List<JoynLiveChannel> {
        val authorization = authorizationHeader(config.country)
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", JoynProtocol.liveStreamsQuery)
            .build()
        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", config.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", config.country.name)
                .header("Joyn-Distribution-Tenant", config.country.graphqlTenant)
                .header("Authorization", authorization)
                .get()
                .build(),
        )
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = errors.toString()
            if (details.contains("INVALID_JWT", true)) throw JoynSessionException(details)
            error("Joyn GraphQL returned ${errors.length()} error(s): ${details.take(220)}")
        }
        val streams = json.optJSONObject("data")?.optJSONArray("liveStreams") ?: return emptyList()
        val now = System.currentTimeMillis() / 1000L
        return buildList {
            for (index in 0 until streams.length()) {
                streams.optJSONObject(index)?.toLiveChannel(now)?.let { channel ->
                    if (channel.isFree || readToken()?.hasAccount == true) add(channel)
                }
            }
        }
    }

    private suspend fun persistedGraphQl(
        config: JoynRuntimeConfig,
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        val authorization = authorizationHeader(config.country)
        val extensions = JSONObject().put(
            "persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", hash),
        )
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operationName)
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", config.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", config.country.name)
                .header("Joyn-Distribution-Tenant", config.country.graphqlTenant)
                .header("Authorization", authorization)
                .get()
                .build(),
        )
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = errors.toString()
            if (details.contains("INVALID_JWT", true)) throw JoynSessionException(details)
            error("Joyn $operationName: ${details.take(260)}")
        }
        return json.optJSONObject("data") ?: error("Joyn $operationName lieferte keine Daten")
    }

    private suspend fun bootstrapConfig(): JoynRuntimeConfig {
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
        val cached = prefs.getString(cacheKey, null)
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        val now = System.currentTimeMillis()
        if (!cached.isNullOrBlank() && now - cachedAt < API_KEY_TTL_MS) {
            return JoynRuntimeConfig(selectedCountry, cached)
        }
        val webBase = "https://www.joyn.${selectedCountry.webSuffix}"
        return runCatching {
            val html = executeText(Request.Builder().url(webBase).header("User-Agent", USER_AGENT).build())
            val sources = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.take(50).toList()
            val key = sources.firstNotNullOfOrNull { source ->
                val absolute = when {
                    source.startsWith("//") -> "https:$source"
                    source.startsWith("http://") || source.startsWith("https://") -> source
                    else -> URI(webBase).resolve(source).toString()
                }
                runCatching {
                    API_KEY.find(executeText(Request.Builder().url(absolute).header("User-Agent", USER_AGENT).build()))
                        ?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
                }.getOrNull()
            } ?: error("API_GW_API_KEY not found in Joyn web bundle")
            prefs.edit().putString(cacheKey, key).putLong("${cacheKey}_at", now).apply()
            JoynRuntimeConfig(selectedCountry, key)
        }.getOrElse { throwable ->
            if (!cached.isNullOrBlank()) JoynRuntimeConfig(selectedCountry, cached) else throw throwable
        }
    }

    private suspend fun authorizationHeader(country: JoynCountry): String = tokenMutex.withLock {
        val existing = readToken()
        val now = System.currentTimeMillis() / 1000L
        val token = when {
            existing == null -> createAnonymousToken(country)
            now < existing.createdAt + existing.expiresIn - 1800L -> existing
            else -> runCatching { refreshToken(country, existing) }
                .getOrElse {
                    if (existing.hasAccount) throw it
                    createAnonymousToken(country)
                }
        }
        "${token.tokenType} ${token.accessToken}"
    }

    private suspend fun forceRefreshAuthorization(country: JoynCountry) = tokenMutex.withLock {
        val existing = readToken()
        if (existing == null) createAnonymousToken(country)
        else runCatching { refreshToken(country, existing) }.getOrElse {
            if (existing.hasAccount) throw it
            prefs.edit().remove("auth_token").apply()
            createAnonymousToken(country)
        }
    }

    private suspend fun resetAnonymousAuthorization(country: JoynCountry) = tokenMutex.withLock {
        prefs.edit().remove("auth_token").remove("client_id").remove("anon_device_id").apply()
        createAnonymousToken(country)
    }

    private fun createAnonymousToken(country: JoynCountry): StoredToken {
        val payload = JSONObject()
            .put("anon_device_id", anonDeviceId())
            .put("client_id", clientId())
            .put("client_name", "web")
        val request = Request.Builder()
            .url("${JoynProtocol.authBaseUrl}/anonymous")
            .header("User-Agent", USER_AGENT)
            .header("Joyn-Country", country.name)
            .header("Joyn-Distribution-Tenant", country.authTenant)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request).toStoredToken().also(::storeToken)
    }

    private fun refreshToken(country: JoynCountry, token: StoredToken): StoredToken {
        val payload = JSONObject()
            .put("refresh_token", token.refreshToken)
            .put("grant_type", token.tokenType)
            .put("client_id", clientId())
            .put("client_name", "web")
        val response = executeJson(
            Request.Builder()
                .url("${JoynProtocol.authBaseUrl}/refresh")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        return StoredToken(
            accessToken = response.optString("access_token").takeIf(String::isNotBlank)
                ?: error("Missing refreshed access token"),
            refreshToken = response.optString("refresh_token").takeIf(String::isNotBlank) ?: token.refreshToken,
            tokenType = response.optString("token_type").takeIf(String::isNotBlank) ?: token.tokenType,
            expiresIn = response.optLong("expires_in").takeIf { it > 0L } ?: token.expiresIn,
            createdAt = System.currentTimeMillis() / 1000L,
            hasAccount = token.hasAccount,
            email = token.email,
        ).also(::storeToken)
    }

    private fun JSONObject.toStoredToken(hasAccount: Boolean = false, email: String? = null): StoredToken = StoredToken(
        accessToken = optString("access_token").takeIf(String::isNotBlank) ?: error("Missing access token"),
        refreshToken = optString("refresh_token").takeIf(String::isNotBlank) ?: error("Missing refresh token"),
        tokenType = optString("token_type").takeIf(String::isNotBlank) ?: "Bearer",
        expiresIn = optLong("expires_in").takeIf { it > 0L } ?: 3600L,
        createdAt = System.currentTimeMillis() / 1000L,
        hasAccount = hasAccount,
        email = email,
    )

    private fun readToken(): StoredToken? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            StoredToken(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                tokenType = json.getString("tokenType"),
                expiresIn = json.getLong("expiresIn"),
                createdAt = json.getLong("createdAt"),
                hasAccount = json.optBoolean("hasAccount", false),
                email = json.optString("email").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun storeToken(token: StoredToken) {
        val json = JSONObject()
            .put("accessToken", token.accessToken)
            .put("refreshToken", token.refreshToken)
            .put("tokenType", token.tokenType)
            .put("expiresIn", token.expiresIn)
            .put("createdAt", token.createdAt)
            .put("hasAccount", token.hasAccount)
        token.email?.let { json.put("email", it) }
        prefs.edit().putString("auth_token", json.toString()).apply()
    }

    private fun postJson(client: OkHttpClient, url: String, payload: JSONObject): JSONObject =
        executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            client,
        )

    private fun clientId(): String = stableUuid("client_id")
    private fun anonDeviceId(): String = stableUuid("anon_device_id")

    private fun stableUuid(key: String): String {
        prefs.getString(key, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun executeJson(request: Request, httpClient: OkHttpClient = client): JSONObject =
        JSONObject(executeText(request, httpClient))

    private fun executeText(request: Request, httpClient: OkHttpClient = client): String =
        executeResponse(request, httpClient).body.string()

    private fun executeResponse(request: Request, httpClient: OkHttpClient = client) =
        httpClient.newCall(request).execute().also { response ->
            if (!response.isSuccessful) {
                val body = response.body.string()
                response.close()
                throw JoynHttpException(response.code, body)
            }
        }

    private fun Throwable.shouldRefreshSession(): Boolean {
        val body = when (this) {
            is JoynHttpException -> responseBody
            is JoynSessionException -> message.orEmpty()
            else -> ""
        }
        return body.contains("INVALID_JWT", true) ||
            body.contains("ENT_VALIDATION_TOKEN_ERROR", true) ||
            (this is JoynHttpException && statusCode in setOf(401, 403))
    }

    private fun Throwable.requiresAnonymousReset(): Boolean {
        val body = when (this) {
            is JoynHttpException -> responseBody
            is JoynAnonymousSessionRestrictedException -> message.orEmpty()
            else -> ""
        }
        return body.contains("ENT_RVOD_Playback_Restricted", true) || this is JoynAnonymousSessionRestrictedException
    }

    private fun JSONObject.toLiveChannel(now: Long): JoynLiveChannel? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        val title = optString("title").takeIf(String::isNotBlank) ?: return null
        val markings = optJSONArray("markings").toStringSet()
        val logo = optJSONObject("brand")?.optJSONObject("livestream")
            ?.optJSONObject("logo")?.optString("url")?.takeIf(String::isNotBlank)
        val mappedPrograms = buildList {
            val events = optJSONArray("epgEvents") ?: JSONArray()
            for (index in 0 until events.length()) events.optJSONObject(index)?.toProgram()?.let(::add)
        }.sortedBy { it.startEpochSeconds ?: Long.MAX_VALUE }
        val current = mappedPrograms.firstOrNull { program ->
            val start = program.startEpochSeconds ?: Long.MIN_VALUE
            val end = program.endEpochSeconds ?: Long.MAX_VALUE
            now in start until end
        }
        return JoynLiveChannel(
            id = id,
            title = title,
            type = optString("type"),
            quality = optString("quality").takeIf(String::isNotBlank),
            markings = markings,
            logoUrl = logo,
            currentProgram = current,
            nextProgram = mappedPrograms.firstOrNull { (it.startEpochSeconds ?: Long.MIN_VALUE) > now },
        )
    }

    private fun JSONObject.toProgram(): JoynProgram? {
        val program = optJSONObject("program") ?: return null
        val title = program.optString("title").takeIf(String::isNotBlank) ?: return null
        val image = program.optJSONObject("thumbnailImage")?.urlValue()
            ?: program.optJSONObject("posterImage")?.urlValue()
            ?: program.optJSONArray("images").firstImageUrl()
        return JoynProgram(
            title = title,
            subtitle = program.optString("secondaryTitle").takeIf(String::isNotBlank)
                ?: program.optString("description").takeIf(String::isNotBlank),
            imageUrl = image,
            startEpochSeconds = optLong("startDate").takeIf { it > 0L },
            endEpochSeconds = optLong("endDate").takeIf { it > 0L },
        )
    }

    private fun JSONObject.toMediaItem(): JoynMediaItem? {
        val typename = optString("__typename")
        val title = optString("title").takeIf(String::isNotBlank)
            ?: optString("name").takeIf(String::isNotBlank)
            ?: return null
        val id = optString("id").takeIf(String::isNotBlank)
            ?: optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank)
            ?: return null
        val type = when (typename) {
            "Movie" -> JoynMediaType.MOVIE
            "Series" -> JoynMediaType.SERIES
            "Episode" -> JoynMediaType.EPISODE
            "Compilation", "CompilationItem" -> JoynMediaType.COMPILATION
            "Extra" -> JoynMediaType.EXTRA
            "SportsMatch", "SportsStage", "SportsCompetition" -> JoynMediaType.SPORT
            "Brand", "ChannelPage" -> JoynMediaType.CHANNEL
            else -> JoynMediaType.UNKNOWN
        }
        val primary = optJSONObject("primaryImage")?.urlValue()
            ?: optJSONObject("thumbnailImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: optJSONObject("heroPortraitImage")?.urlValue()
            ?: optJSONObject("heroPortrait")?.urlValue()
            ?: optJSONArray("images").firstImageUrl()
        val backdrop = optJSONObject("heroLandscapeImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: primary
        val logo = optJSONObject("artLogoImage")?.urlValue()
            ?: optJSONObject("logo")?.urlValue()
            ?: optJSONObject("brand")?.optJSONObject("logo")?.urlValue()
        return JoynMediaItem(
            id = id,
            title = title,
            description = optString("description").takeIf(String::isNotBlank)
                ?: optString("secondaryTitle").takeIf(String::isNotBlank),
            path = optString("path").takeIf(String::isNotBlank),
            type = type,
            imageUrl = primary,
            backdropUrl = backdrop,
            logoUrl = logo,
            videoId = optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank),
            seasonId = optJSONObject("season")?.optString("id")?.takeIf(String::isNotBlank),
            seasonNumber = optJSONObject("season")?.optInt("number")?.takeIf { it > 0 }
                ?: optJSONObject("season")?.optInt("seasonNumber")?.takeIf { it > 0 },
            episodeNumber = optInt("number").takeIf { it > 0 },
            licenseTypes = optJSONArray("licenseTypes").toStringSet(),
            markings = optJSONArray("markings").toStringSet(),
        )
    }

    private fun JSONArray?.toMediaItems(): List<JoynMediaItem> = buildList {
        val source = this@toMediaItems ?: return@buildList
        for (index in 0 until source.length()) source.optJSONObject(index)?.toMediaItem()?.let(::add)
    }

    private fun JSONArray?.appendObjectsTo(target: MutableList<JSONObject>) {
        val source = this ?: return
        for (index in 0 until source.length()) source.optJSONObject(index)?.let(target::add)
    }

    private fun JSONObject.urlValue(): String? = optString("url").takeIf(String::isNotBlank)

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONArray?.firstImageUrl(): String? {
        val source = this ?: return null
        var fallback: String? = null
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val url = item.optString("url").takeIf(String::isNotBlank) ?: continue
            if (fallback == null) fallback = url
            if (item.optString("type") in setOf("LIVE_STILL", "PRIMARY", "HERO_LANDSCAPE")) return url
        }
        return fallback
    }

    private fun catalogueTitle(path: String): String = when (path) {
        "/serien" -> "Serien"
        "/filme" -> "Filme"
        "/sport" -> "Sport"
        else -> "Mediathek"
    }

    private data class StoredToken(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
        val hasAccount: Boolean = false,
        val email: String? = null,
    )

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val names = cookies.map { it.name }.toSet()
            this.cookies.removeAll { it.name in names && it.matches(url) }
            this.cookies += cookies
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    private class JoynHttpException(val statusCode: Int, val responseBody: String) :
        IOException("Joyn HTTP $statusCode: ${responseBody.take(240)}")
    private class JoynSessionException(details: String) : IOException(details)
    private class JoynAnonymousSessionRestrictedException(details: String) : IOException(details)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val MAX_PLAYBACK_ATTEMPTS = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)

        private const val HASH_LANDING_PAGE = "b71b3871aebfe266b63a4bf7daaa35645e17be2469b850265ba75146fa60affc"
        private const val HASH_LANDING_BLOCKS = "1655591f83b0dc1508ad4d52c5f37f72d410f48ad08c3e5f2de8622f86a21c68"
        private const val HASH_SEARCH = "bb2bab6cbe17321d7eddd5006e7f40765faedd79790b193a59d83f4640694856"
        private const val HASH_SEASONS = "e867452d17ef36e5c077db5cdcad7563a9aebede497c24ac8fae779723bc462d"
        private const val HASH_EPISODES = "ee2396bb1b7c9f800e5cefd0b341271b7213fceb4ebe18d5a30dab41d703009f"
        private const val HASH_MOVIE_DETAIL = "9ae6bcd8c45a5e350438d1cc415a022fe053e938c93438509f60ae3abb425fa7"
        private const val HASH_ACCOUNT = "55ebb3812b45628017ee6c7f36f0b88a94e9778b9f11ad8a6fc05849182c07ec"
    }
}
