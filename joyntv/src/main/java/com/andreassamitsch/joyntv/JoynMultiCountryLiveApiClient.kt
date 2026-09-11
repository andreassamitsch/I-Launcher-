package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Country-explicit Joyn client used by the combined AT/DE/CH Live-TV view.
 *
 * Every market keeps its own account session. Premium streams are only exposed when the retained
 * account for that country actually has an active Joyn PLUS subscription; a plain login alone is
 * not enough. This keeps channels in the UI that the active account can really play.
 */
internal class JoynMultiCountryLiveApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)
    private val tokenMutex = Mutex()
    private val anonymousTokens = mutableMapOf<JoynCountry, LiveToken>()
    private val subscriptionCache = mutableMapOf<JoynCountry, SubscriptionSnapshot>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun loadLiveChannels(country: JoynCountry): List<JoynLiveChannel> = withContext(Dispatchers.IO) {
        val config = bootstrapConfig(country)
        runCatching { loadLiveChannelsOnce(config, forceRefreshAccount = false) }
            .recoverCatching { error ->
                if (!error.shouldRetryAuthorization()) throw error
                invalidateAnonymousToken(country)
                subscriptionCache.remove(country)
                loadLiveChannelsOnce(config, forceRefreshAccount = true)
            }
            .getOrThrow()
    }

    suspend fun resolveLivePlayback(country: JoynCountry, channelId: String): JoynPlayback =
        withContext(Dispatchers.IO) {
            val config = bootstrapConfig(country)
            runCatching { resolveLivePlaybackOnce(config, channelId, forceRefreshAccount = false) }
                .recoverCatching { error ->
                    if (!error.shouldRetryAuthorization()) throw error
                    invalidateAnonymousToken(country)
                    subscriptionCache.remove(country)
                    resolveLivePlaybackOnce(config, channelId, forceRefreshAccount = true)
                }
                .getOrThrow()
        }

    fun hasStoredAccountSession(country: JoynCountry): Boolean =
        readPersistentToken(country)?.hasAccount == true

    private suspend fun loadLiveChannelsOnce(
        config: JoynRuntimeConfig,
        forceRefreshAccount: Boolean,
    ): List<JoynLiveChannel> {
        val authorization = authorization(config.country, forceRefreshAccount)
        val hasPlus = authorization.hasAccount && hasActivePlus(config, authorization.header)
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", JoynProtocol.liveStreamsQuery)
            .build()
        val body = executeText(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", config.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", config.country.name)
                .header("Joyn-Distribution-Tenant", config.country.graphqlTenant)
                .header("Authorization", authorization.header)
                .get()
                .build(),
        )
        val json = JSONObject(body)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = errors.toString()
            if (details.contains("INVALID_JWT", true)) throw LiveSessionException(details)
            error("Joyn ${config.country.name} Live GraphQL: ${details.take(260)}")
        }

        val streams = json.optJSONObject("data")?.optJSONArray("liveStreams") ?: return emptyList()
        val now = System.currentTimeMillis() / 1000L
        return buildList {
            for (index in 0 until streams.length()) {
                streams.optJSONObject(index)?.toLiveChannel(now)?.let { channel ->
                    if (channel.isFree || hasPlus) add(channel)
                }
            }
        }
    }

    private fun hasActivePlus(config: JoynRuntimeConfig, authorizationHeader: String): Boolean {
        val now = System.currentTimeMillis()
        subscriptionCache[config.country]
            ?.takeIf { now - it.checkedAtEpochMs < SUBSCRIPTION_CACHE_MS }
            ?.let { return it.hasPlus }

        val extensions = JSONObject().put(
            "persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", HASH_ACCOUNT),
        )
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "GetMeState")
            .addQueryParameter("variables", JSONObject().toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val body = executeText(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", config.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", config.country.name)
                .header("Joyn-Distribution-Tenant", config.country.graphqlTenant)
                .header("Authorization", authorizationHeader)
                .get()
                .build(),
        )
        val json = JSONObject(body)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val details = errors.toString()
            if (details.contains("INVALID_JWT", true)) throw LiveSessionException(details)
            return false
        }
        val hasPlus = json.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optJSONObject("subscriptionsData")
            ?.optJSONObject("config")
            ?.optBoolean("hasActivePlus", false)
            ?: false
        subscriptionCache[config.country] = SubscriptionSnapshot(hasPlus, now)
        return hasPlus
    }

    private suspend fun resolveLivePlaybackOnce(
        config: JoynRuntimeConfig,
        channelId: String,
        forceRefreshAccount: Boolean,
    ): JoynPlayback {
        val authorization = authorization(config.country, forceRefreshAccount)
        val entitlementPayload = JSONObject()
            .put("content_id", channelId)
            .put("content_type", "LIVE")
            .toString()
        val entitlementBody = executeText(
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", authorization.header)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(entitlementPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (
            entitlementBody.contains("ENT_USER_VPN_DETECTED", ignoreCase = true) ||
            entitlementBody.contains("VPN_DETECTED", ignoreCase = true)
        ) {
            error("Joyn erkennt den ${config.country.name}-Exit weiterhin als VPN.")
        }
        val entitlementJson = JSONObject(entitlementBody)
        val entitlementToken = entitlementJson.optString("entitlement_token").takeIf(String::isNotBlank)
            ?: run {
                if (
                    entitlementBody.contains("INVALID_JWT", true) ||
                    entitlementBody.contains("ENT_VALIDATION_TOKEN_ERROR", true)
                ) {
                    throw LiveSessionException(entitlementBody)
                }
                error("Joyn ${config.country.name} lieferte kein Live-Entitlement: ${compact(entitlementBody)}")
            }

        val signature = JoynProtocol.playbackSignature(entitlementToken)
        val playlistUrl = "${JoynProtocol.playbackBaseUrl}/channel/$channelId/playlist"
            .toHttpUrl().newBuilder()
            .addQueryParameter("signature", signature)
            .build()
        val playlistBody = executeText(
            Request.Builder()
                .url(playlistUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $entitlementToken")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(JoynProtocol.playerPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val playlist = JSONObject(playlistBody)
        val manifest = playlist.optString("manifestUrl").takeIf(String::isNotBlank)
            ?: error("Joyn ${config.country.name} lieferte kein DASH-Manifest: ${compact(playlistBody)}")
        return JoynPlayback(
            manifestUrl = manifest,
            licenseUrl = playlist.optString("licenseUrl").takeIf(String::isNotBlank),
            certificateUrl = playlist.optString("certificateUrl").takeIf(String::isNotBlank),
        )
    }

    private suspend fun authorization(country: JoynCountry, forceRefreshAccount: Boolean): LiveAuthorization =
        tokenMutex.withLock {
            val now = System.currentTimeMillis() / 1000L
            val persisted = readPersistentToken(country)?.takeIf { it.hasAccount }
            if (persisted != null) {
                val validUntil = persisted.createdAtEpochSeconds + persisted.expiresInSeconds - TOKEN_MARGIN_SECONDS
                val accountToken = when {
                    !forceRefreshAccount && now < validUntil -> persisted
                    else -> refreshAccountToken(country, persisted)
                }
                return@withLock LiveAuthorization(
                    header = "${accountToken.tokenType} ${accountToken.accessToken}",
                    hasAccount = true,
                )
            }

            val token = anonymousTokens[country]
                ?.takeIf { now < it.createdAtEpochSeconds + it.expiresInSeconds - TOKEN_MARGIN_SECONDS }
                ?: createAnonymousToken(country).also { anonymousTokens[country] = it }
            LiveAuthorization(
                header = "${token.tokenType} ${token.accessToken}",
                hasAccount = false,
            )
        }

    private fun refreshAccountToken(country: JoynCountry, token: LiveToken): LiveToken {
        if (token.refreshToken.isBlank()) {
            throw LiveSessionException("Joyn ${country.name}: gespeicherte Anmeldung hat keinen Refresh-Token. Bitte einmal neu anmelden.")
        }
        val payload = JSONObject()
            .put("refresh_token", token.refreshToken)
            .put("grant_type", token.tokenType)
            .put("client_id", stableUuid("client_id"))
            .put("client_name", "web")
        val response = JSONObject(
            executeText(
                Request.Builder()
                    .url("${JoynProtocol.authBaseUrl}/refresh")
                    .header("User-Agent", USER_AGENT)
                    .header("Joyn-Country", country.name)
                    .header("Joyn-Distribution-Tenant", country.authTenant)
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ),
        )
        val refreshed = LiveToken(
            accessToken = response.optString("access_token").takeIf(String::isNotBlank)
                ?: throw LiveSessionException("Joyn ${country.name}: Refresh lieferte keinen Access-Token."),
            refreshToken = response.optString("refresh_token").takeIf(String::isNotBlank) ?: token.refreshToken,
            tokenType = response.optString("token_type").takeIf(String::isNotBlank) ?: token.tokenType,
            expiresInSeconds = response.optLong("expires_in").takeIf { it > 0L } ?: token.expiresInSeconds,
            createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
            hasAccount = true,
            email = token.email,
        )
        subscriptionCache.remove(country)
        regionSettings.saveSessionJson(country, refreshed.toPersistentJson())
        return refreshed
    }

    private fun readPersistentToken(country: JoynCountry): LiveToken? {
        val raw = regionSettings.sessionJson(country) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LiveToken(
                accessToken = json.getString("accessToken"),
                refreshToken = json.optString("refreshToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresInSeconds = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAtEpochSeconds = json.optLong("createdAt").takeIf { it > 0L } ?: 0L,
                hasAccount = json.optBoolean("hasAccount", false),
                email = json.optString("email").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private suspend fun invalidateAnonymousToken(country: JoynCountry) {
        tokenMutex.withLock { anonymousTokens.remove(country) }
    }

    private fun createAnonymousToken(country: JoynCountry): LiveToken {
        val payload = JSONObject()
            .put("anon_device_id", stableUuid("multi_live_anon_device_${country.name}"))
            .put("client_id", stableUuid("multi_live_client_${country.name}"))
            .put("client_name", "web")
        val response = JSONObject(
            executeText(
                Request.Builder()
                    .url("${JoynProtocol.authBaseUrl}/anonymous")
                    .header("User-Agent", USER_AGENT)
                    .header("Joyn-Country", country.name)
                    .header("Joyn-Distribution-Tenant", country.authTenant)
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ),
        )
        return LiveToken(
            accessToken = response.optString("access_token").takeIf(String::isNotBlank)
                ?: error("Joyn ${country.name}: anonymer Access-Token fehlt."),
            refreshToken = response.optString("refresh_token"),
            tokenType = response.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer",
            expiresInSeconds = response.optLong("expires_in").takeIf { it > 0L } ?: 3600L,
            createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
            hasAccount = false,
        )
    }

    private fun bootstrapConfig(country: JoynCountry): JoynRuntimeConfig {
        val cacheKey = "api_key_${country.name}"
        val cached = prefs.getString(cacheKey, null)
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        val now = System.currentTimeMillis()
        if (!cached.isNullOrBlank() && now - cachedAt < API_KEY_TTL_MS) {
            return JoynRuntimeConfig(country, cached)
        }

        val webBase = "https://www.joyn.${country.webSuffix}"
        return runCatching {
            val html = executeText(
                Request.Builder().url(webBase).header("User-Agent", USER_AGENT).get().build(),
            )
            val sources = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.take(50).toList()
            val key = sources.firstNotNullOfOrNull { source ->
                val absolute = when {
                    source.startsWith("//") -> "https:$source"
                    source.startsWith("http://") || source.startsWith("https://") -> source
                    else -> URI(webBase).resolve(source).toString()
                }
                runCatching {
                    API_KEY.find(
                        executeText(
                            Request.Builder().url(absolute).header("User-Agent", USER_AGENT).get().build(),
                        ),
                    )?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
                }.getOrNull()
            } ?: error("API_GW_API_KEY not found in Joyn ${country.name} web bundle")
            prefs.edit().putString(cacheKey, key).putLong("${cacheKey}_at", now).apply()
            JoynRuntimeConfig(country, key)
        }.getOrElse { error ->
            if (!cached.isNullOrBlank()) JoynRuntimeConfig(country, cached) else throw error
        }
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

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (index in 0 until source.length()) {
            source.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.urlValue(): String? = optString("url").takeIf(String::isNotBlank)

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

    private fun stableUuid(key: String): String {
        prefs.getString(key, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun LiveToken.toPersistentJson(): String = JSONObject()
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("tokenType", tokenType)
        .put("expiresIn", expiresInSeconds)
        .put("createdAt", createdAtEpochSeconds)
        .put("hasAccount", hasAccount)
        .apply { email?.let { put("email", it) } }
        .toString()

    private fun executeText(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw LiveHttpException(response.code, body)
            body
        }

    private fun Throwable.shouldRetryAuthorization(): Boolean {
        val details = when (this) {
            is LiveHttpException -> responseBody
            is LiveSessionException -> message.orEmpty()
            else -> message.orEmpty()
        }
        return (this is LiveHttpException && statusCode in setOf(401, 403)) ||
            details.contains("INVALID_JWT", ignoreCase = true) ||
            details.contains("ENT_VALIDATION_TOKEN_ERROR", ignoreCase = true)
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(260)

    private data class LiveAuthorization(val header: String, val hasAccount: Boolean)

    private data class LiveToken(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresInSeconds: Long,
        val createdAtEpochSeconds: Long,
        val hasAccount: Boolean,
        val email: String? = null,
    )

    private data class SubscriptionSnapshot(
        val hasPlus: Boolean,
        val checkedAtEpochMs: Long,
    )

    private class LiveHttpException(val statusCode: Int, val responseBody: String) :
        IOException("Joyn Live HTTP $statusCode: ${responseBody.take(260)}")

    private class LiveSessionException(details: String) : IOException(details)

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val TOKEN_MARGIN_SECONDS = 300L
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val SUBSCRIPTION_CACHE_MS = 10L * 60L * 1000L
        private const val HASH_ACCOUNT = "55ebb3812b45628017ee6c7f36f0b88a94e9778b9f11ad8a6fc05849182c07ec"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)
    }
}
