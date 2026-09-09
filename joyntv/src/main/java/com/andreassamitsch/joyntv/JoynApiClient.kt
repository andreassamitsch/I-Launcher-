package com.andreassamitsch.joyntv

import android.content.Context
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun loadLiveChannels(): List<JoynLiveChannel> {
        val config = bootstrapConfig()
        val authorization = authorizationHeader(config.country)
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", JoynProtocol.liveStreamsQuery)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-api-key", config.apiKey)
            .header("Joyn-Platform", "web")
            .header("Joyn-Country", config.country.name)
            .header("Joyn-Distribution-Tenant", config.country.graphqlTenant)
            .header("Authorization", authorization)
            .get()
            .build()

        val json = executeJson(request)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            error("Joyn GraphQL returned ${errors.length()} error(s)")
        }
        val streams = json.optJSONObject("data")?.optJSONArray("liveStreams")
            ?: return emptyList()
        val now = System.currentTimeMillis() / 1000L

        return buildList {
            for (index in 0 until streams.length()) {
                val item = streams.optJSONObject(index) ?: continue
                val mapped = item.toLiveChannel(now) ?: continue
                if (mapped.isFree) add(mapped)
            }
        }
    }

    suspend fun resolveLivePlayback(channelId: String): JoynPlayback {
        val config = bootstrapConfig()
        val authorization = authorizationHeader(config.country)
        val entitlementBody = JSONObject()
            .put("content_id", channelId)
            .put("content_type", "LIVE")
            .toString()
        val entitlementRequest = Request.Builder()
            .url(JoynProtocol.entitlementUrl)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", authorization)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(entitlementBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val entitlementToken = executeJson(entitlementRequest)
            .optString("entitlement_token")
            .takeIf { it.isNotBlank() }
            ?: error("Joyn did not return an entitlement token")

        val signature = JoynProtocol.playbackSignature(entitlementToken)
        val playlistUrl = "${JoynProtocol.playbackBaseUrl}/channel/$channelId/playlist"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("signature", signature)
            .build()
        val playlistRequest = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $entitlementToken")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(JoynProtocol.playerPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val playlist = executeJson(playlistRequest)
        val manifest = playlist.optString("manifestUrl").takeIf { it.isNotBlank() }
            ?: error("Joyn did not return a DASH manifest")

        return JoynPlayback(
            manifestUrl = manifest,
            licenseUrl = playlist.optString("licenseUrl").takeIf { it.isNotBlank() },
            certificateUrl = playlist.optString("certificateUrl").takeIf { it.isNotBlank() },
        )
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
            val html = executeText(
                Request.Builder().url(webBase).header("User-Agent", USER_AGENT).build(),
            )
            val sources = SCRIPT_SRC.findAll(html)
                .map { it.groupValues[1] }
                .take(40)
                .toList()
            val key = sources.firstNotNullOfOrNull { source ->
                val absolute = when {
                    source.startsWith("//") -> "https:$source"
                    source.startsWith("http://") || source.startsWith("https://") -> source
                    else -> URI(webBase).resolve(source).toString()
                }
                runCatching {
                    API_KEY.find(
                        executeText(
                            Request.Builder().url(absolute).header("User-Agent", USER_AGENT).build(),
                        ),
                    )?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
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
                .getOrElse { createAnonymousToken(country) }
        }
        "${token.tokenType} ${token.accessToken}"
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
        val request = Request.Builder()
            .url("${JoynProtocol.authBaseUrl}/refresh")
            .header("User-Agent", USER_AGENT)
            .header("Joyn-Country", country.name)
            .header("Joyn-Distribution-Tenant", country.authTenant)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = executeJson(request)
        return StoredToken(
            accessToken = response.optString("access_token").takeIf { it.isNotBlank() }
                ?: error("Missing refreshed access token"),
            refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: token.refreshToken,
            tokenType = response.optString("token_type").takeIf { it.isNotBlank() }
                ?: token.tokenType,
            expiresIn = response.optLong("expires_in").takeIf { it > 0L }
                ?: token.expiresIn,
            createdAt = System.currentTimeMillis() / 1000L,
        ).also(::storeToken)
    }

    private fun JSONObject.toStoredToken(): StoredToken = StoredToken(
        accessToken = optString("access_token").takeIf { it.isNotBlank() }
            ?: error("Missing access token"),
        refreshToken = optString("refresh_token").takeIf { it.isNotBlank() }
            ?: error("Missing refresh token"),
        tokenType = optString("token_type").takeIf { it.isNotBlank() } ?: "Bearer",
        expiresIn = optLong("expires_in").takeIf { it > 0L } ?: 3600L,
        createdAt = System.currentTimeMillis() / 1000L,
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
        prefs.edit().putString("auth_token", json.toString()).apply()
    }

    private fun clientId(): String = stableUuid("client_id")
    private fun anonDeviceId(): String = stableUuid("anon_device_id")

    private fun stableUuid(key: String): String {
        prefs.getString(key, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun executeJson(request: Request): JSONObject = JSONObject(executeText(request))

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Joyn HTTP ${response.code}: ${body.take(180)}")
        }
        body
    }

    private fun JSONObject.toLiveChannel(now: Long): JoynLiveChannel? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val markings = optJSONArray("markings").toStringSet()
        val brand = optJSONObject("brand")
        val logo = brand?.optJSONObject("livestream")
            ?.optJSONObject("logo")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
        val events = optJSONArray("epgEvents")
        val mappedPrograms = buildList {
            if (events != null) {
                for (index in 0 until events.length()) {
                    events.optJSONObject(index)?.toProgram()?.let(::add)
                }
            }
        }.sortedBy { it.startEpochSeconds ?: Long.MAX_VALUE }
        val current = mappedPrograms.firstOrNull { program ->
            val start = program.startEpochSeconds ?: Long.MIN_VALUE
            val end = program.endEpochSeconds ?: Long.MAX_VALUE
            now in start until end
        }
        val next = mappedPrograms.firstOrNull { (it.startEpochSeconds ?: Long.MIN_VALUE) > now }
        return JoynLiveChannel(
            id = id,
            title = title,
            type = optString("type"),
            quality = optString("quality").takeIf { it.isNotBlank() },
            markings = markings,
            logoUrl = logo,
            currentProgram = current,
            nextProgram = next,
        )
    }

    private fun JSONObject.toProgram(): JoynProgram? {
        val start = optLong("startDate").takeIf { it > 0L }
        val end = optLong("endDate").takeIf { it > 0L }
        val program = optJSONObject("program") ?: return null
        val title = program.optString("title").takeIf { it.isNotBlank() } ?: return null
        val subtitle = program.optString("secondaryTitle").takeIf { it.isNotBlank() }
            ?: program.optString("description").takeIf { it.isNotBlank() }
        val image = program.optJSONObject("thumbnailImage")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: program.optJSONObject("posterImage")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: program.optJSONArray("images").firstImageUrl()
        return JoynProgram(title, subtitle, image, start, end)
    }

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (index in 0 until source.length()) {
            source.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray?.firstImageUrl(): String? {
        val source = this ?: return null
        var fallback: String? = null
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (fallback == null) fallback = url
            if (item.optString("type") == "LIVE_STILL") return url
        }
        return fallback
    }

    private data class StoredToken(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)
    }
}
