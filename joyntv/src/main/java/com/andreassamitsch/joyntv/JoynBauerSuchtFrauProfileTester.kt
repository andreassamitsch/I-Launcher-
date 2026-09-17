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
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Read-only VOD diagnostics for "Bauer sucht Frau", Staffel 23, Folge 3.
 *
 * The probe discovers the exact FREE AT catalogue entry and compares the existing direct-AT
 * account/player profiles with deliberately split AT/CH request paths. The hybrid cases use a
 * dedicated temporary JoynMysteriumProxyBridge backed by the already verified CH residential
 * lease. No process ProxySelector, persisted region/proxy setting, Joyn account session, player
 * route, cache or favourite is modified. No Media3 player, DRM licence or media segment is used.
 */
internal class JoynBauerSuchtFrauProfileTester(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mysteriumSettings = JoynMysteriumSettings(appContext)

    suspend fun run(): JoynBauerSuchtFrauProfileReport = withContext(Dispatchers.IO) {
        val chConfig = mysteriumSettings.lastSuccessful(JoynCountry.CH)
            ?: error("Kein gespeicherter Mysterium-Residential-Proxy für CH vorhanden. Bitte zuerst den normalen Schweiz-Pfad einmal erfolgreich laden.")
        if (mysteriumSettings.leaseNeedsRefresh(JoynCountry.CH)) {
            error("Der gespeicherte CH-Residential-Lease ist abgelaufen bzw. zu alt. Bitte zuerst den normalen Schweiz-Pfad einmal erfolgreich laden.")
        }
        if (!chConfig.isUsable || !chConfig.isMysterium) {
            error("Der gespeicherte CH-Pfad ist kein verwendbarer Mysterium-Residential-HTTP-Proxy.")
        }

        val bridgeFailures = mutableListOf<String>()
        JoynMysteriumProxyBridge(
            remoteHost = chConfig.host.trim(),
            remotePort = chConfig.port,
            username = chConfig.username,
            password = chConfig.password,
            onFailure = { reason -> synchronized(bridgeFailures) { bridgeFailures += reason } },
        ).use { chBridge ->
            val directClient = client(Proxy.NO_PROXY)
            val chClient = client(Proxy(Proxy.Type.HTTP, chBridge.localAddress))
            try {
                val apiKey = apiKeyForAt(directClient)
                val anonymousAuthorization = anonymousAuthorization(directClient)
                val accountAuthorization = runCatching { storedAccountAuthorization(directClient) }
                val discoveryAuthorization = accountAuthorization.getOrNull() ?: anonymousAuthorization
                val episode = findTargetEpisode(directClient, apiKey, discoveryAuthorization)

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
                            resolveAndInspect(episode.videoId, anonymousAuthorization, web1080, directClient, directClient, directClient)
                        },
                    )
                    add(
                        runCase("Anonym · Browser · 2160") {
                            resolveAndInspect(episode.videoId, anonymousAuthorization, web2160, directClient, directClient, directClient)
                        },
                    )
                    add(
                        runCase("Anonym · Android TV · 2160 · L5") {
                            resolveAndInspect(episode.videoId, anonymousAuthorization, androidTv2160, directClient, directClient, directClient)
                        },
                    )
                    add(
                        runCase("AT-Account · Browser · 1080") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, web1080, directClient, directClient, directClient)
                        },
                    )
                    add(
                        runCase("AT-Account · Android TV · 2160 · L5") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, androidTv2160, directClient, directClient, directClient)
                        },
                    )
                    add(
                        runCase("AT-Account · alle VOD-Requests über CH-Netz") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, web1080, chClient, chClient, chClient)
                        },
                    )
                    add(
                        runCase("AT-Account · AT-Entitlement → CH-Playlist + MPD") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, web1080, directClient, chClient, chClient)
                        },
                    )
                    add(
                        runCase("AT-Account · AT-Entitlement + Playlist → nur MPD über CH") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, web1080, directClient, directClient, chClient)
                        },
                    )
                    add(
                        runCase("AT-Account · AT-Entitlement → CH-Playback · Android TV 2160 · L5") {
                            val authorization = requireAccount(accountAuthorization)
                            resolveAndInspect(episode.videoId, authorization, androidTv2160, directClient, chClient, chClient)
                        },
                    )
                }

                val leaseLabel = buildString {
                    append(chConfig.source.ifBlank { "Mysterium · Residential · CH" })
                    mysteriumSettings.lastExpiresAt(JoynCountry.CH)
                        .takeIf(String::isNotBlank)
                        ?.let { append(" · gültig bis $it") }
                }
                val accountStatus = accountAuthorization.fold(
                    onSuccess = { value ->
                        if (value != null) "Gespeicherte Joyn-AT-Session verfügbar."
                        else "Keine gespeicherte Joyn-AT-Session vorhanden."
                    },
                    onFailure = { error ->
                        "AT-Account-Session konnte für den Test nicht verwendet werden: " +
                            (error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(280)
                    },
                ) + " · CH-Testpfad: $leaseLabel" +
                    bridgeFailures.distinct().take(3).takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = " · Proxy-Hinweis: ", separator = " · ")
                        .orEmpty()

                JoynBauerSuchtFrauProfileReport(
                    seriesTitle = episode.seriesTitle,
                    episodeTitle = episode.episodeTitle,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    videoId = episode.videoId,
                    seriesPath = episode.seriesPath,
                    accountAvailable = accountAuthorization.getOrNull() != null,
                    accountStatus = accountStatus,
                    results = results,
                )
            } finally {
                directClient.connectionPool.evictAll()
                chClient.connectionPool.evictAll()
            }
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

    private fun requireAccount(accountAuthorization: Result<String?>): String =
        accountAuthorization.getOrThrow()
            ?: error("Kein gespeicherter Joyn-AT-Account vorhanden. Bitte zuerst im Konto-Tab für Österreich anmelden.")

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
                val script = executeText(client, Request.Builder().url(absolute).header("User-Agent", USER_AGENT).get().build())
                API_KEY.find(script)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            }.getOrNull()
        } ?: error("API_GW_API_KEY wurde im Joyn-AT-Webbundle nicht gefunden.")
    }

    private fun findTargetEpisode(client: OkHttpClient, apiKey: String, authorization: String): EpisodeRef {
        val searchData = persistedGraphQl(
            client = client,
            apiKey = apiKey,
            authorization = authorization,
            operationName = SEARCH_OPERATION,
            persistedHash = SEARCH_HASH,
            variables = JSONObject().put("text", SERIES_SEARCH_TEXT).put("first", 32).put("offset", 0),
        )
        val results = searchData.optJSONObject("search")?.optJSONArray("results")
            ?: error("Joyn-Suche lieferte keine Ergebnisse für '$SERIES_SEARCH_TEXT'.")
        val seriesCandidates = buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                if (!item.optString("__typename").equals("Series", true)) continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                val title = item.optString("title").takeIf(String::isNotBlank) ?: continue
                val path = item.optString("path").takeIf(String::isNotBlank) ?: continue
                add(SeriesRef(id, title, path))
            }
        }
        val wanted = normalizeTitle(SERIES_SEARCH_TEXT)
        val series = seriesCandidates.firstOrNull { normalizeTitle(it.title) == wanted }
            ?: seriesCandidates.firstOrNull { normalizeTitle(it.title).contains(wanted) }
            ?: error("'Bauer sucht Frau' wurde in der Joyn-AT-Suche nicht als Serie gefunden.")

        val seriesData = persistedGraphQl(
            client = client,
            apiKey = apiKey,
            authorization = authorization,
            operationName = SERIES_OPERATION,
            persistedHash = SERIES_HASH,
            variables = JSONObject().put("path", series.path).put("licenseFilter", LICENSE_FILTER),
        )
        val allSeasons = seriesData.optJSONObject("page")?.optJSONObject("series")?.optJSONArray("allSeasons")
            ?: error("Joyn lieferte für '${series.title}' keine Staffeln.")
        val season = findSeason(allSeasons, TARGET_SEASON_NUMBER)
            ?: error("Staffel $TARGET_SEASON_NUMBER wurde im Joyn-$LICENSE_FILTER-Katalog für '${series.title}' nicht gefunden.")

        val episodeData = persistedGraphQl(
            client = client,
            apiKey = apiKey,
            authorization = authorization,
            operationName = EPISODES_OPERATION,
            persistedHash = EPISODES_HASH,
            variables = JSONObject().put("id", season.id).put("licenseFilter", LICENSE_FILTER).put("first", 1000).put("offset", 0),
        )
        val episodes = episodeData.optJSONObject("season")?.optJSONArray("episodes")
            ?: error("Joyn lieferte für Staffel $TARGET_SEASON_NUMBER keine Episoden.")
        val target = findEpisode(episodes, TARGET_EPISODE_NUMBER)
            ?: error("Staffel $TARGET_SEASON_NUMBER, Folge $TARGET_EPISODE_NUMBER wurde im Joyn-$LICENSE_FILTER-Katalog nicht mit Video-ID gefunden.")
        return EpisodeRef(series.title, series.path, target.title, season.number, target.number, target.videoId)
    }

    private fun findSeason(seasons: JSONArray, targetNumber: Int): SeasonRef? {
        for (index in 0 until seasons.length()) {
            val item = seasons.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            val number = item.optInt("number", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            if (number == targetNumber) return SeasonRef(id, number)
        }
        return null
    }

    private fun findEpisode(episodes: JSONArray, targetNumber: Int): EpisodeCandidate? {
        for (index in 0 until episodes.length()) {
            val item = episodes.optJSONObject(index) ?: continue
            val number = item.optInt("number", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            if (number != targetNumber) continue
            val videoId = item.optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank) ?: continue
            val title = item.optString("title").takeIf(String::isNotBlank) ?: "Episode"
            return EpisodeCandidate(title, number, videoId)
        }
        return null
    }

    private fun persistedGraphQl(
        client: OkHttpClient,
        apiKey: String,
        authorization: String,
        operationName: String,
        persistedHash: String,
        variables: JSONObject,
    ): JSONObject {
        val extensions = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", persistedHash))
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operationName)
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val body = executeText(
            client,
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.graphqlTenant)
                .header("Authorization", authorization)
                .get().build(),
        )
        val root = JSONObject(body)
        root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
            error("Joyn GraphQL $operationName: ${it.toString().take(420)}")
        }
        return root.optJSONObject("data") ?: error("Joyn GraphQL $operationName lieferte keine Daten.")
    }

    private fun anonymousAuthorization(client: OkHttpClient): String {
        val payload = JSONObject().put("anon_device_id", UUID.randomUUID().toString()).put("client_id", UUID.randomUUID().toString()).put("client_name", "web").toString()
        val body = executeText(
            client,
            Request.Builder().url("${JoynProtocol.authBaseUrl}/anonymous")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        val json = JSONObject(body)
        val token = json.optString("access_token").takeIf(String::isNotBlank) ?: error("Joyn AT: anonymer Test-Token fehlt.")
        val type = json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer"
        return "$type $token"
    }

    private fun storedAccountAuthorization(client: OkHttpClient): String? {
        val raw = prefs.getString(KEY_AT_ACCOUNT_SESSION, null) ?: return null
        val json = JSONObject(raw)
        if (!json.optBoolean("hasAccount", false)) return null
        val tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer"
        val accessToken = json.optString("accessToken").takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session enthält keinen Access-Token.")
        val expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L
        val createdAt = json.optLong("createdAt").takeIf { it > 0L } ?: 0L
        val now = System.currentTimeMillis() / 1000L
        if (createdAt > 0L && now < createdAt + expiresIn - ACCOUNT_TOKEN_MARGIN_SECONDS) return "$tokenType $accessToken"

        val refreshToken = json.optString("refreshToken").takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session ist abgelaufen und enthält keinen Refresh-Token.")
        val clientId = prefs.getString("client_id", null)?.takeIf(String::isNotBlank)
            ?: error("Gespeicherte AT-Account-Session ist abgelaufen und die Joyn client_id fehlt.")
        val payload = JSONObject().put("refresh_token", refreshToken).put("grant_type", tokenType).put("client_id", clientId).put("client_name", "web").toString()
        val body = executeText(
            client,
            Request.Builder().url("${JoynProtocol.authBaseUrl}/refresh")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", JoynCountry.AT.name)
                .header("Joyn-Distribution-Tenant", JoynCountry.AT.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        val refreshed = JSONObject(body)
        val refreshedAccess = refreshed.optString("access_token").takeIf(String::isNotBlank)
            ?: error("Temporärer AT-Account-Refresh lieferte keinen Access-Token.")
        val refreshedType = refreshed.optString("token_type").takeIf(String::isNotBlank) ?: tokenType
        return "$refreshedType $refreshedAccess"
    }

    private fun resolveAndInspect(
        videoId: String,
        authorization: String,
        playerPayload: String,
        entitlementClient: OkHttpClient,
        playlistClient: OkHttpClient,
        manifestClient: OkHttpClient,
    ): PlaybackInspection {
        val entitlement = entitlement(videoId, authorization, entitlementClient)
        val playback = playlist(videoId, entitlement, playerPayload, playlistClient)
        return inspectPlayback(playback, manifestClient)
    }

    private fun entitlement(videoId: String, authorization: String, client: OkHttpClient): String {
        val payload = JSONObject().put("content_id", videoId).put("content_type", "VOD").toString()
        val body = executeText(
            client,
            Request.Builder().url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", authorization)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        return JSONObject(body).optString("entitlement_token").takeIf(String::isNotBlank)
            ?: error("Kein VOD-Entitlement: ${compact(body)}")
    }

    private fun playlist(videoId: String, entitlementToken: String, playerPayload: String, client: OkHttpClient): JoynPlayback {
        val signature = JoynProtocol.playbackSignature(playerPayload, entitlementToken)
        val url = "${JoynProtocol.playbackBaseUrl}/asset/$videoId/playlist".toHttpUrl().newBuilder()
            .addQueryParameter("signature", signature).build()
        val body = executeText(
            client,
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $entitlementToken")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(playerPayload.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        val json = JSONObject(body)
        val manifest = json.optString("manifestUrl").takeIf(String::isNotBlank)
            ?: error("Keine DASH-Manifest-URL: ${compact(body)}")
        return JoynPlayback(manifest, json.optString("licenseUrl").takeIf(String::isNotBlank), json.optString("certificateUrl").takeIf(String::isNotBlank))
    }

    private fun inspectPlayback(playback: JoynPlayback, client: OkHttpClient): PlaybackInspection {
        val mpd = executeText(client, Request.Builder().url(playback.manifestUrl).header("User-Agent", USER_AGENT).get().build())
        val quality = parseDashQuality(mpd)
        val uri = runCatching { URI(playback.manifestUrl) }.getOrNull()
        return PlaybackInspection(uri?.host ?: playback.manifestUrl.substringBefore('/'), quality)
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
                        if (video) reps += VideoRepresentation(width, height, bandwidth, parseFrameRate(parser.attribute("frameRate") ?: adaptationFrameRate))
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
        val best = reps.maxWithOrNull(compareBy<VideoRepresentation> { it.height ?: -1 }.thenBy { it.width ?: -1 }.thenBy { it.bandwidth ?: -1L })
            ?: error("Keine Videoqualität ermittelbar.")
        return DashQuality(best.width, best.height, reps.mapNotNull { it.bandwidth }.maxOrNull(), reps.mapNotNull { it.frameRate }.maxOrNull(), reps.size)
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)?.takeIf(String::isNotBlank)
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

    private fun runCase(label: String, block: () -> PlaybackInspection): JoynPuls4HybridCaseResult = runCatching(block).fold(
        onSuccess = { JoynPuls4HybridCaseResult(label, true, it.quality, it.manifestHost) },
        onFailure = { JoynPuls4HybridCaseResult(label, false, error = (it.message ?: it.javaClass.simpleName).replace('\n', ' ').take(420)) },
    )

    private fun executeText(client: OkHttpClient, request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${compact(body)}")
        body
    }

    private fun normalizeTitle(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(320)

    private data class SeriesRef(val id: String, val title: String, val path: String)
    private data class SeasonRef(val id: String, val number: Int?)
    private data class EpisodeCandidate(val title: String, val number: Int?, val videoId: String)
    private data class EpisodeRef(val seriesTitle: String, val seriesPath: String, val episodeTitle: String, val seasonNumber: Int?, val episodeNumber: Int?, val videoId: String)
    private data class PlaybackInspection(val manifestHost: String, val quality: DashQuality)
    private data class VideoRepresentation(val width: Int?, val height: Int?, val bandwidth: Long?, val frameRate: Double?)

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_AT_ACCOUNT_SESSION = "auth_token_AT"
        private const val ACCOUNT_TOKEN_MARGIN_SECONDS = 120L
        private const val SERIES_SEARCH_TEXT = "Bauer sucht Frau"
        private const val TARGET_SEASON_NUMBER = 23
        private const val TARGET_EPISODE_NUMBER = 3
        private const val LICENSE_FILTER = "FREE"
        private const val SEARCH_OPERATION = "SearchQ"
        private const val SEARCH_HASH = "bb2bab6cbe17321d7eddd5006e7f40765faedd79790b193a59d83f4640694856"
        private const val SERIES_OPERATION = "SeriesDetailNewPageStatic"
        private const val SERIES_HASH = "e867452d17ef36e5c077db5cdcad7563a9aebede497c24ac8fae779723bc462d"
        private const val EPISODES_OPERATION = "Season"
        private const val EPISODES_HASH = "ee2396bb1b7c9f800e5cefd0b341271b7213fceb4ebe18d5a30dab41d703009f"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SCRIPT_SRC = Regex("""<script[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("""API_GW_API_KEY.{0,80}?value:\"([^\"]+)\"""", RegexOption.DOT_MATCHES_ALL)
    }
}

internal data class JoynBauerSuchtFrauProfileReport(
    val seriesTitle: String,
    val episodeTitle: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val videoId: String,
    val seriesPath: String,
    val accountAvailable: Boolean,
    val accountStatus: String,
    val results: List<JoynPuls4HybridCaseResult>,
)
