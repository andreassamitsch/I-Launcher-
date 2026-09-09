package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** PIN-aware VOD entitlement path.
 *
 * Joyn validates the parental PIN on the entitlement request itself. This client deliberately
 * reuses the session and API-key cache created by JoynApiClient and only takes over the VOD
 * resolution when a PIN has to be supplied.
 */
internal class JoynPinPlaybackApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val core = JoynApiClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun resolveVodPlayback(contentRef: String, pin: String): JoynPlayback {
        require(pin.matches(Regex("^\\d{4}$"))) { "Der Jugendschutz-PIN muss genau 4 Ziffern haben." }
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val session = ensureSession(forceRefresh = attempt > 0)
                val videoId = if (contentRef.startsWith('/')) resolveMovieVideoId(contentRef, session) else contentRef
                return resolvePlayback(videoId, pin, session)
            } catch (error: Throwable) {
                if (error is JoynPinRequiredException || error is JoynPinInvalidException) throw error
                lastError = error
                if (attempt > 0 || !error.message.orEmpty().contains("INVALID_JWT", true)) throw error
            }
        }
        throw lastError ?: IOException("Joyn PIN-Wiedergabe fehlgeschlagen")
    }

    private fun resolvePlayback(videoId: String, pin: String, session: Session): JoynPlayback {
        val entitlementBody = JSONObject()
            .put("content_id", videoId)
            .put("content_type", "VOD")
            .put("pin", pin)
            .toString()
        val entitlement = executeJson(
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", session.authorization)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(entitlementBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val raw = entitlement.toString()
        if (raw.contains("ENT_PINInvalid", true)) throw JoynPinInvalidException()
        if (raw.contains("ENT_PINRequired", true)) throw JoynPinRequiredException()
        if (raw.contains("INVALID_JWT", true)) throw IOException(raw)

        val entitlementToken = entitlement.optString("entitlement_token").takeIf(String::isNotBlank)
            ?: error("Joyn lieferte trotz PIN kein Entitlement-Token: ${raw.take(260)}")
        val signature = JoynProtocol.playbackSignature(entitlementToken)
        val playlistUrl = "${JoynProtocol.playbackBaseUrl}/asset/$videoId/playlist"
            .toHttpUrl().newBuilder()
            .addQueryParameter("signature", signature)
            .build()
        val playlist = executeJson(
            Request.Builder()
                .url(playlistUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $entitlementToken")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(JoynProtocol.playerPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        return JoynPlayback(
            manifestUrl = playlist.optString("manifestUrl").takeIf(String::isNotBlank)
                ?: error("Joyn lieferte kein DASH-Manifest"),
            licenseUrl = playlist.optString("licenseUrl").takeIf(String::isNotBlank),
            certificateUrl = playlist.optString("certificateUrl").takeIf(String::isNotBlank),
        )
    }

    private fun resolveMovieVideoId(path: String, session: Session): String {
        val extensions = JSONObject().put(
            "persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", HASH_MOVIE_DETAIL),
        )
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "PageMovieDetailStatic")
            .addQueryParameter("variables", JSONObject().put("path", path).toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val data = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", session.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", session.country.name)
                .header("Joyn-Distribution-Tenant", session.country.graphqlTenant)
                .header("Authorization", session.authorization)
                .get()
                .build(),
        ).optJSONObject("data")
        val movie = data?.optJSONObject("page")?.optJSONObject("movie")
            ?: error("Joyn Film '$path' wurde nicht gefunden")
        return movie.optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank)
            ?: error("Joyn Film '${movie.optString("title").ifBlank { path }}' enthält keine Video-ID")
    }

    private suspend fun ensureSession(forceRefresh: Boolean): Session {
        if (forceRefresh) {
            runCatching { core.loadLiveChannels() }
        }
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
        var token = readToken()
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000L
        val keyFresh = !prefs.getString(cacheKey, null).isNullOrBlank() &&
            nowMs - prefs.getLong("${cacheKey}_at", 0L) < API_KEY_TTL_MS
        val tokenFresh = token != null && now < token.createdAt + token.expiresIn - 1800L
        if (!keyFresh || !tokenFresh) {
            core.loadLiveChannels()
            token = readToken()
        }
        return Session(
            country = selectedCountry,
            apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
                ?: error("Joyn API-Key ist nicht verfügbar"),
            authorization = token?.let { "${it.tokenType} ${it.accessToken}" }
                ?: error("Joyn Sitzung ist nicht verfügbar"),
        )
    }

    private fun readToken(): Token? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Token(
                accessToken = json.getString("accessToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val body = response.body.string()
        val parsed = runCatching { JSONObject(body) }.getOrElse { JSONObject().put("raw", body) }
        val raw = parsed.toString()
        if (raw.contains("ENT_PINInvalid", true)) throw JoynPinInvalidException()
        if (raw.contains("ENT_PINRequired", true)) throw JoynPinRequiredException()
        if (!response.isSuccessful) throw IOException("Joyn HTTP ${response.code}: ${body.take(300)}")
        parsed
    }

    private data class Session(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
    )

    private data class Token(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val HASH_MOVIE_DETAIL = "9ae6bcd8c45a5e350438d1cc415a022fe053e938c93438509f60ae3abb425fa7"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
