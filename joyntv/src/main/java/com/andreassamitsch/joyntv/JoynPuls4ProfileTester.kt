package com.andreassamitsch.joyntv

import android.content.Context
import android.util.Xml
import java.io.IOException
import java.io.StringReader
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Direct-AT-only diagnostics for PULS 4 account and playback-device behaviour.
 *
 * Unlike the hybrid probe this class never touches a Mysterium route. OkHttp is explicitly pinned
 * to Proxy.NO_PROXY. Stored Joyn AT account data is read only; when the access token has expired a
 * temporary refresh is performed in memory and is deliberately NOT written back to preferences.
 * No repository route, account session, cache, playback pin, DRM license or media segment is changed
 * or requested. The probe stops after inspecting the DASH MPD.
 */
internal class JoynPuls4ProfileTester(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)

    suspend fun run(): JoynPuls4ProfileReport = withContext(Dispatchers.IO) {
        val client = directClient()
        try {
            val apiKey = apiKeyForAt(client)
            val anonymousAuthorization = anonymousAuthorization(client)
            val channel = findPuls4At(client, apiKey, anonymousAuthorization)
            val accountAuthorization = runCatching { storedAccountAuthorization(client) }

            val web1080 = JoynProtocol.playerPayload
            val web2160 = JSONObject(JoynProtocol.playerPayload)
                .put("maxResolution", 2160)
                .toString()
            val androidTv2160 = JSONObject()
                .put("enableDolbyAtmos", true)
                .put("enableSubtitles", true)
                .put("manufacturer", "")
                .put("maxResolution", 2160)
                .put("model", "")
                .put("platform", "android-tv")
                .put("protectionSystem", "widevine")
                .put("streamingFormat", "dash")
                .put("variantName", "")
                .put("version", "v1")
                .put("maxSecurityLevel", 5)
                .toString()

            val results = buildList {
                add(
                    runCase("Anonym · Browser · 1080 – Referenz") {
                        resolveAndInspect(channel.id, anonymousAuthorization, web1080, client)
                    },
                )
                add(
                    runCase("Anonym · Browser · 2160") {
                        resolveAndInspect(channel.id, anonymousAuthorization, web2160, client)
                    },
                )
                add(
                    runCase("Anonym · Android TV · 2160 · L5") {
                        resolveAndInspect(channel.id, anonymousAuthorization, androidTv2160, client)
                    },
                )
                add(
                    runCase("AT-Account · Browser · 1080") {
                        val authorization = accountAuthorization.getOrThrow()
                            ?: error("Kein gespeicherter Joyn-AT-Account vorhanden. Bitte zuerst im Konto-Tab für Österreich anmelden.")
                        resolveAndInspect(channel.id, authorization, web1080, client)
                    },
                )
                add(
                    runCase("AT-Account · Android TV · 2160 · L5") {
                        val authorization = accountAuthorization.getOrThrow()
                            ?: error("Kein gespeicherter Joyn-AT-Account vorhanden. Bitte zuerst im Konto-Tab für Österreich anmelden.")
                        resolveAndInspect(channel.id, authorization, androidTv2160, client)
                    },
                )
            }

            JoynPuls4ProfileReport(
                channelTitle = channel.title,
                channelId = channel.id,
                accountAvailable = accountAuthorization.getOrNull() != null,
                accountStatus = accountAuthorization.fold(
                    onSuccess = { value ->
                        if (value != null) "Gespeicherte Joyn-AT-Session verfügbar."
                        else "Keine gespeicherte Joyn-AT-Session vorhanden."
                    },
                    onFailure = { error ->
                        "AT-Account-Session konnte für den Test nicht verwendet werden: " +
                            (error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(280)
                    },
                ),
                results = results,
            )
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private fun directClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun apiKeyForAt(client: OkHttpClient): String {
        prefs.getString("api_key_AT", null)?.takeIf(String::isNotBlank)?.let { return it }

        val webBase = "https://www.joyn.at"
        val html = executeText(client, Request.Builder().url(webBase).header("User-Agent", USER_AGENT).get().build())
        val sources = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.take(50).toList()
        return sources.firstNotNullOfOrNull { source ->
            val absolute = when {
                source.startsWith("//") -> "https:$source"
                source.startsWith("http://") || source.startsWith("https://") -> source
                else -> URI(webBase).resolve(source).toString()
            }
            runCatching {
                val script = executeText(
                    client,
                    Request.Builder().url(absolute).header("User-Agent", USER_AGENT).get().build(),
                )
                API_KEY.find(script)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            }.getOrNull()
        } ?: error("API_GW_API_KEY wurde im Joyn-AT-Webbundle nicht gefunden.")
    }

    private fun findPuls4At(
        client: OkHttpClient,
        apiKey: String,
        authorization: String,
    ): ChannelRef {
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", JoynProtocol.liveStreamsQuery)
            .build()
        val body = executeText(
            client,
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.graphqlTenant)
                .header("Authorization", authorization)
                .get()
                .build(),
        )
        val root = JSONObject(body)
        root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
            error("Joyn AT Live GraphQL: ${it.toString().take(320)}")
        }
        val streams = root.optJSONObject("data")?.optJSONArray("liveStreams")
            ?: error("Joyn AT lieferte keine Live-Senderliste.")
        val candidates = buildList {
            for (index in 0 until streams.length()) {
                val item = streams.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                val title = item.optString("title").takeIf(String::isNotBlank) ?: continue
                if (normalizeStation(title).startsWith("puls4")) add(ChannelRef(id, title))
            }
        }
        return candidates.firstOrNull { normalizeStation(it.title) == "puls4" }
            ?: candidates.firstOrNull()
            ?: error("PULS 4 wurde in Joyn AT nicht gefunden.")
    }

    private fun anonymousAuthorization(client: OkHttpClient): String {
        val payload = JSONObject()
            .put("anon_device_id", UUID.randomUUID().toString())
            .put("client_id", UUID.randomUUID().toString())
            .put("client_name", "web")
            .toString()
        val body = executeText(
            client,
            Request.Builder()
                .url("${JoynProtocol.authBaseUrl}/anonymous")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val json = JSONObject(body)
        val token = json.optString("access_token").takeIf(String::isNotBlank)
            ?: error("Joyn AT: anonymer Test-Token fehlt.")
        val type = json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer"
        return "$type $token"
    }

    /** Returns null when no account is stored. Any refresh stays in memory and is never persisted. */
    private fun storedAccountAuthorization(client: OkHttpClient): String? {
        val raw = regionSettings.sessionJson(JoynCountry.AT) ?: return null
        val json = JSONObject(raw)
        if (!json.optBoolean("hasAccount", false)) return null

        val tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer"
        val accessToken = json.optString("accessToken").takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session enthält keinen Access-Token.")
        val expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L
        val createdAt = json.optLong("createdAt").takeIf { it > 0L } ?: 0L
        val now = System.currentTimeMillis() / 1000L
        if (createdAt > 0L && now < createdAt + expiresIn - ACCOUNT_TOKEN_MARGIN_SECONDS) {
            return "$tokenType $accessToken"
        }

        val refreshToken = json.optString("refreshToken").takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session ist abgelaufen und enthält keinen Refresh-Token.")
        val clientId = prefs.getString("client_id", null)?.takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session ist abgelaufen und die Joyn client_id fehlt.")
        val payload = JSONObject()
            .put("refresh_token", refreshToken)
            // Keep the same refresh contract as the production multi-country Joyn client.
            .put("grant_type", tokenType)
            .put("client_id", clientId)
            .put("client_name", "web")
            .toString()
        val body = executeText(
            client,
            Request.Builder()
                .url("${JoynProtocol.authBaseUrl}/refresh")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val refreshed = JSONObject(body)
        val refreshedAccess = refreshed.optString("access_token").takeIf(String::isNotBlank)
            ?: error("Temporärer AT-Account-Refresh lieferte keinen Access-Token.")
        val refreshedType = refreshed.optString("token_type").takeIf(String::isNotBlank) ?: tokenType
        return "$refreshedType $refreshedAccess"
    }

    private fun resolveAndInspect(
        channelId: String,
        authorization: String,
        playerPayload: String,
        client: OkHttpClient,
    ): PlaybackInspection {
        val entitlement = entitlement(channelId, authorization, client)
        val playback = playlist(channelId, entitlement, playerPayload, client)
        return inspectPlayback(playback, client)
    }

    private fun entitlement(channelId: String, authorization: String, client: OkHttpClient): String {
        val payload = JSONObject()
            .put("content_id", channelId)
            .put("content_type", "LIVE")
            .toString()
        val body = executeText(
            client,
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", authorization)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        return JSONObject(body).optString("entitlement_token").takeIf(String::isNotBlank)
            ?: error("Kein Live-Entitlement: ${compact(body)}")
    }

    private fun playlist(
        channelId: String,
        entitlementToken: String,
        playerPayload: String,
        client: OkHttpClient,
    ): JoynPlayback {
        val signature = JoynProtocol.playbackSignature(playerPayload, entitlementToken)
        val url = "${JoynProtocol.playbackBaseUrl}/channel/$channelId/playlist"
            .toHttpUrl().newBuilder()
            .addQueryParameter("signature", signature)
            .build()
        val body = executeText(
            client,
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $entitlementToken")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(playerPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val json = JSONObject(body)
        val manifest = json.optString("manifestUrl").takeIf(String::isNotBlank)
            ?: error("Keine DASH-Manifest-URL: ${compact(body)}")
        return JoynPlayback(
            manifestUrl = manifest,
            licenseUrl = json.optString("licenseUrl").takeIf(String::isNotBlank),
            certificateUrl = json.optString("certificateUrl").takeIf(String::isNotBlank),
        )
    }

    private fun inspectPlayback(playback: JoynPlayback, client: OkHttpClient): PlaybackInspection {
        val mpd = executeText(
            client,
            Request.Builder()
                .url(playback.manifestUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        )
        val quality = parseDashQuality(mpd)
        val uri = runCatching { URI(playback.manifestUrl) }.getOrNull()
        return PlaybackInspection(
            manifestHost = uri?.host ?: playback.manifestUrl.substringBefore('/'),
            quality = quality,
        )
    }

    private fun parseDashQuality(mpd: String): DashQuality {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(mpd))
        var event = parser.eventType
        var adaptationVideo = false
        var adaptationFrameRate: String? = null
        val reps = mutableListOf<VideoRepresentation>()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "AdaptationSet" -> {
                        val contentType = parser.attribute("contentType")
                        val mimeType = parser.attribute("mimeType")
                        adaptationVideo = contentType.equals("video", true) || mimeType?.startsWith("video/") == true
                        adaptationFrameRate = parser.attribute("frameRate")
                    }

                    "Representation" -> {
                        val mimeType = parser.attribute("mimeType")
                        val width = parser.attribute("width")?.toIntOrNull()
                        val height = parser.attribute("height")?.toIntOrNull()
                        val bandwidth = parser.attribute("bandwidth")?.toLongOrNull()
                        val video = adaptationVideo || mimeType?.startsWith("video/") == true || (height ?: 0) > 0
                        if (video) {
                            reps += VideoRepresentation(
                                width = width,
                                height = height,
                                bandwidth = bandwidth,
                                frameRate = parseFrameRate(parser.attribute("frameRate") ?: adaptationFrameRate),
                            )
                        }
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "AdaptationSet") {
                    adaptationVideo = false
                    adaptationFrameRate = null
                }
            }
            event = parser.next()
        }

        if (reps.isEmpty()) error("DASH-MPD enthält keine erkennbaren Video-Repräsentationen.")
        val best = reps.maxWithOrNull(
            compareBy<VideoRepresentation> { it.height ?: -1 }
                .thenBy { it.width ?: -1 }
                .thenBy { it.bandwidth ?: -1L },
        ) ?: error("Keine Videoqualität ermittelbar.")
        return DashQuality(
            width = best.width,
            height = best.height,
            maxBitrate = reps.mapNotNull { it.bandwidth }.maxOrNull(),
            maxFrameRate = reps.mapNotNull { it.frameRate }.maxOrNull(),
            representationCount = reps.size,
        )
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) {
                return getAttributeValue(index)?.takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun parseFrameRate(value: String?): Double? {
        val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if ('/' in text) {
            val numerator = text.substringBefore('/').toDoubleOrNull() ?: return null
            val denominator = text.substringAfter('/').toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            return numerator / denominator
        }
        return text.toDoubleOrNull()
    }

    private fun runCase(
        label: String,
        block: () -> PlaybackInspection,
    ): JoynPuls4HybridCaseResult = runCatching(block).fold(
        onSuccess = { inspection ->
            JoynPuls4HybridCaseResult(
                label = label,
                success = true,
                quality = inspection.quality,
                manifestHost = inspection.manifestHost,
            )
        },
        onFailure = { error ->
            JoynPuls4HybridCaseResult(
                label = label,
                success = false,
                error = (error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(420),
            )
        },
    )

    private fun executeText(client: OkHttpClient, request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${compact(body)}")
            }
            body
        }

    private fun normalizeStation(value: String): String = value
        .lowercase()
        .replace("+", "plus")
        .replace(Regex("[^a-z0-9]+"), "")

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(320)

    private data class ChannelRef(val id: String, val title: String)
    private data class PlaybackInspection(val manifestHost: String, val quality: DashQuality)
    private data class VideoRepresentation(
        val width: Int?,
        val height: Int?,
        val bandwidth: Long?,
        val frameRate: Double?,
    )

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val ACCOUNT_TOKEN_MARGIN_SECONDS = 120L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)
    }
}

internal data class JoynPuls4ProfileReport(
    val channelTitle: String,
    val channelId: String,
    val accountAvailable: Boolean,
    val accountStatus: String,
    val results: List<JoynPuls4HybridCaseResult>,
)
