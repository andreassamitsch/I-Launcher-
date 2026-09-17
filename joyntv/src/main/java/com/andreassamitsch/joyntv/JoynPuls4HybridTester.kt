package com.andreassamitsch.joyntv

import android.content.Context
import android.util.Xml
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
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
 * Read-only diagnostic probe for PULS 4 Austria.
 *
 * This deliberately does NOT use JoynRepository's country-routing methods. Every request gets an
 * explicit OkHttp route: Proxy.NO_PROXY for the Austrian/direct side and a dedicated temporary
 * loopback JoynMysteriumProxyBridge for the Swiss side. No process ProxySelector, persisted Joyn
 * region, normal Joyn token, live cache, playback pin or Mysterium lease selection is modified.
 *
 * The probe also never starts Media3 and never requests DRM licenses or media segments. It stops
 * after downloading the DASH MPD and reports the video representations Joyn offered for each
 * request combination.
 */
internal class JoynPuls4HybridTester(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mysteriumSettings = JoynMysteriumSettings(appContext)

    suspend fun run(): JoynPuls4HybridReport = withContext(Dispatchers.IO) {
        val chConfig = mysteriumSettings.lastSuccessful(JoynCountry.CH)
            ?: error("Kein gespeicherter Mysterium-Residential-Proxy für CH vorhanden.")
        if (mysteriumSettings.leaseNeedsRefresh(JoynCountry.CH)) {
            error("Der gespeicherte CH-Residential-Lease ist abgelaufen bzw. zu alt. Bitte zuerst den normalen Schweiz-Pfad einmal erfolgreich laden.")
        }
        if (!chConfig.isUsable || !chConfig.isMysterium) {
            error("Der gespeicherte CH-Pfad ist kein verwendbarer Mysterium-Residential-HTTP-Proxy.")
        }

        val failures = mutableListOf<String>()
        JoynMysteriumProxyBridge(
            remoteHost = chConfig.host.trim(),
            remotePort = chConfig.port,
            username = chConfig.username,
            password = chConfig.password,
            onFailure = { reason -> synchronized(failures) { failures += reason } },
        ).use { chBridge ->
            val directClient = client(Proxy.NO_PROXY)
            val chClient = client(Proxy(Proxy.Type.HTTP, chBridge.localAddress))

            val apiKey = apiKeyForAt(directClient)
            val channel = findPuls4At(directClient, apiKey)
            val atAuthorization = anonymousAuthorization(JoynCountry.AT, directClient)

            val results = mutableListOf<JoynPuls4HybridCaseResult>()
            results += runCase("AT / AT – Referenz") {
                resolveAndInspect(
                    channelId = channel.id,
                    authorization = atAuthorization,
                    entitlementClient = directClient,
                    playlistClient = directClient,
                    manifestClient = directClient,
                )
            }
            results += runCase("AT-Identität / CH-Netz") {
                resolveAndInspect(
                    channelId = channel.id,
                    authorization = atAuthorization,
                    entitlementClient = chClient,
                    playlistClient = chClient,
                    manifestClient = chClient,
                )
            }
            results += runCase("CH-Identität / CH-Netz / AT-Content") {
                val chAuthorization = anonymousAuthorization(JoynCountry.CH, chClient)
                resolveAndInspect(
                    channelId = channel.id,
                    authorization = chAuthorization,
                    entitlementClient = chClient,
                    playlistClient = chClient,
                    manifestClient = chClient,
                )
            }
            results += runCase("AT-Entitlement / CH-Playback") {
                val entitlement = entitlement(channel.id, atAuthorization, directClient)
                val playback = playlist(channel.id, entitlement, chClient)
                inspectPlayback(playback, chClient)
            }

            directClient.connectionPool.evictAll()
            chClient.connectionPool.evictAll()

            JoynPuls4HybridReport(
                channelTitle = channel.title,
                channelId = channel.id,
                chLeaseSource = chConfig.source.ifBlank { "Mysterium · Residential · CH" },
                chLeaseExpiresAt = mysteriumSettings.lastExpiresAt(JoynCountry.CH).takeIf(String::isNotBlank),
                results = results,
                bridgeFailures = failures.distinct().take(6),
            )
        }
    }

    private fun client(proxy: Proxy): OkHttpClient = OkHttpClient.Builder()
        .proxy(proxy)
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

    private fun findPuls4At(client: OkHttpClient, apiKey: String): ChannelRef {
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
                .header("Authorization", anonymousAuthorization(JoynCountry.AT, client))
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

    private fun anonymousAuthorization(country: JoynCountry, client: OkHttpClient): String {
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
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val json = JSONObject(body)
        val token = json.optString("access_token").takeIf(String::isNotBlank)
            ?: error("Joyn ${country.name}: anonymer Test-Token fehlt.")
        val type = json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer"
        return "$type $token"
    }

    private fun resolveAndInspect(
        channelId: String,
        authorization: String,
        entitlementClient: OkHttpClient,
        playlistClient: OkHttpClient,
        manifestClient: OkHttpClient,
    ): PlaybackInspection {
        val entitlement = entitlement(channelId, authorization, entitlementClient)
        val playback = playlist(channelId, entitlement, playlistClient)
        return inspectPlayback(playback, manifestClient)
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

    private fun playlist(channelId: String, entitlementToken: String, client: OkHttpClient): JoynPlayback {
        val signature = JoynProtocol.playbackSignature(entitlementToken)
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
                .post(JoynProtocol.playerPayload.toRequestBody(JSON_MEDIA_TYPE))
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
        val request = Request.Builder()
            .url(playback.manifestUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val mpd = executeText(client, request)
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
        val maxBitrate = reps.mapNotNull { it.bandwidth }.maxOrNull()
        val maxFps = reps.mapNotNull { it.frameRate }.maxOrNull()
        return DashQuality(
            width = best.width,
            height = best.height,
            maxBitrate = maxBitrate,
            maxFrameRate = maxFps,
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)
    }
}

internal data class JoynPuls4HybridReport(
    val channelTitle: String,
    val channelId: String,
    val chLeaseSource: String,
    val chLeaseExpiresAt: String?,
    val results: List<JoynPuls4HybridCaseResult>,
    val bridgeFailures: List<String>,
)

internal data class JoynPuls4HybridCaseResult(
    val label: String,
    val success: Boolean,
    val quality: DashQuality? = null,
    val manifestHost: String? = null,
    val error: String? = null,
)

internal data class DashQuality(
    val width: Int?,
    val height: Int?,
    val maxBitrate: Long?,
    val maxFrameRate: Double?,
    val representationCount: Int,
)
