package com.andreassamitsch.joyntv

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class JoynProxyDiscoveryProgress(
    val message: String,
    val attempted: Int = 0,
    val total: Int = 0,
)

internal data class JoynProxyDiscoveryResult(
    val config: JoynProxyConfig?,
    val candidates: Int,
    val attempted: Int,
    val message: String,
)

internal data class JoynPublicProxyCandidate(
    val host: String,
    val port: Int,
    val transport: JoynProxyTransport,
    val source: String,
    val anonymity: String = "",
    val uptimePercent: Double = 0.0,
    val reportedLatencyMs: Double = Double.MAX_VALUE,
)

/**
 * Test-only automatic proxy discovery.
 *
 * Public lists are downloaded over the direct connection and are never trusted on their own.
 * Every candidate must prove the expected exit country, establish HTTPS to Joyn, create a fresh
 * anonymous Joyn session, load live channels through GraphQL and obtain a real LIVE entitlement.
 * This rejects public proxy IPs which Joyn classifies as VPN/proxy before they are activated.
 */
internal class JoynPublicProxyResolver {
    private val directClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun findBest(
        country: JoynCountry,
        allTraffic: Boolean,
        apiKey: String?,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        if (apiKey.isNullOrBlank()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Der Joyn API-Schlüssel für ${country.name} ist noch nicht im Cache. Bitte Joyn einmal ohne Proxy starten und die automatische Proxy-Suche danach erneut öffnen.",
            )
        }

        onProgress(JoynProxyDiscoveryProgress("Lade aktuelle ProxyScrape-Liste für ${country.name} …"))
        val primary = fetchProxyScrape(country)

        val fallback = if (primary.size < MIN_PRIMARY_CANDIDATES) {
            onProgress(JoynProxyDiscoveryProgress("Ergänze Kandidaten aus einer zweiten öffentlichen Liste …"))
            fetchProxifly(country)
        } else {
            emptyList()
        }

        val candidates = rankCandidates(primary + fallback)
            .distinctBy { "${it.transport}:${it.host}:${it.port}" }
            .take(MAX_CANDIDATES)

        if (candidates.isEmpty()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Für ${country.name} wurden aktuell keine technisch passenden HTTP/HTTPS- oder SOCKS5-Proxys gefunden.",
            )
        }

        var attempted = 0
        for (batch in candidates.chunked(PARALLELISM)) {
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Teste ${attempted + 1}–${attempted + batch.size} von ${candidates.size}: Land, Joyn API und Live-Freigabe …",
                    attempted = attempted,
                    total = candidates.size,
                ),
            )

            val working = coroutineScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) { probe(country, candidate, apiKey) }
                }.awaitAll().filterNotNull()
            }
            attempted += batch.size

            val best = working.minByOrNull { it.latencyMs }
            if (best != null) {
                val config = JoynProxyConfig(
                    enabled = true,
                    automatic = true,
                    transport = best.candidate.transport,
                    host = best.candidate.host,
                    port = best.candidate.port,
                    username = "",
                    password = "",
                    allTraffic = allTraffic,
                    source = best.candidate.source,
                    latencyMs = best.latencyMs,
                    lastVerifiedAtEpochMs = System.currentTimeMillis(),
                )
                val message =
                    "Joyn-tauglicher ${country.name}-${config.transport.name}-Proxy: ${config.host}:${config.port} · ${config.latencyMs} ms · ${config.source}"
                onProgress(
                    JoynProxyDiscoveryProgress(
                        message = message,
                        attempted = attempted,
                        total = candidates.size,
                    ),
                )
                return JoynProxyDiscoveryResult(
                    config = config,
                    candidates = candidates.size,
                    attempted = attempted,
                    message = message,
                )
            }
        }

        return JoynProxyDiscoveryResult(
            config = null,
            candidates = candidates.size,
            attempted = attempted,
            message = "Keiner der $attempted getesteten ${country.name}-Proxys bestand Joyns API- und Live-Prüfung. Von Joyn als VPN/Proxy erkannte IPs werden absichtlich nicht aktiviert.",
        )
    }

    private suspend fun fetchProxyScrape(country: JoynCountry): List<JoynPublicProxyCandidate> {
        val countryCode = country.name.lowercase()
        val httpsCountryUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/$countryCode/https/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$countryCode/https/data.json",
        )
        val combinedCountryUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/$countryCode/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$countryCode/data.json",
        )

        val httpsCandidates = fetchFirst(httpsCountryUrls)
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
        if (httpsCandidates.size >= MIN_PRIMARY_CANDIDATES) return httpsCandidates

        val countryCandidates = fetchFirst(combinedCountryUrls)
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
        val mergedCountry = (httpsCandidates + countryCandidates)
            .distinctBy { "${it.transport}:${it.host}:${it.port}" }
        if (mergedCountry.isNotEmpty()) return mergedCountry

        val globalHttpsUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/https/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/protocols/https/data.json",
        )
        val globalSocks5Urls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/socks5/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/protocols/socks5/data.json",
        )
        val globalHttps = fetchFirst(globalHttpsUrls)
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
        val globalSocks5 = fetchFirst(globalSocks5Urls)
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
        return (globalHttps + globalSocks5)
            .distinctBy { "${it.transport}:${it.host}:${it.port}" }
    }

    private suspend fun fetchProxifly(country: JoynCountry): List<JoynPublicProxyCandidate> {
        val httpsUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/https/data.json",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/https/data.json",
        )
        val socks5Urls = listOf(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.json",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.json",
        )
        val httpsCandidates = fetchFirst(httpsUrls)
            ?.let { parseProxifly(it, country) }
            .orEmpty()
        val socks5Candidates = fetchFirst(socks5Urls)
            ?.let { parseProxifly(it, country) }
            .orEmpty()
        return (httpsCandidates + socks5Candidates)
            .distinctBy { "${it.transport}:${it.host}:${it.port}" }
    }

    private suspend fun fetchFirst(urls: List<String>): String? = withContext(Dispatchers.IO) {
        for (url in urls) {
            val body = runCatching {
                directClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body.string()
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) return@withContext body
        }
        null
    }

    private fun parseProxyScrape(
        body: String,
        country: JoynCountry,
    ): List<JoynPublicProxyCandidate> = runCatching {
        val expectedCountry = country.name
        val array = JSONArray(body)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.optString("country_code").equals(expectedCountry, ignoreCase = true)) continue
                val protocol = item.optString("protocol").lowercase()
                val transport = when (protocol) {
                    "http", "https" -> {
                        val supportsHttps = item.optBoolean("ssl", false) || protocol == "https"
                        if (!supportsHttps) continue
                        JoynProxyTransport.HTTP
                    }
                    "socks5" -> JoynProxyTransport.SOCKS5
                    else -> continue
                }

                val anonymity = item.optString("anonymity").lowercase()
                if (anonymity == "transparent") continue
                val host = item.optString("ip").trim()
                val port = item.optInt("port", 0)
                if (host.isBlank() || port !in 1..65535) continue

                add(
                    JoynPublicProxyCandidate(
                        host = host,
                        port = port,
                        transport = transport,
                        source = "ProxyScrape",
                        anonymity = anonymity,
                        uptimePercent = item.optDouble("uptime_percent", 0.0),
                        reportedLatencyMs = item.optDouble("latency_ms", Double.MAX_VALUE),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parseProxifly(
        body: String,
        country: JoynCountry,
    ): List<JoynPublicProxyCandidate> = runCatching {
        val expectedCountry = country.name
        val array = JSONArray(body)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val geo = item.optJSONObject("geolocation")
                if (!geo?.optString("country").orEmpty().equals(expectedCountry, ignoreCase = true)) continue

                val protocol = item.optString("protocol").lowercase()
                val transport = when (protocol) {
                    "http", "https" -> {
                        val supportsHttps = item.optBoolean("https", false) || protocol == "https"
                        if (!supportsHttps) continue
                        JoynProxyTransport.HTTP
                    }
                    "socks5" -> JoynProxyTransport.SOCKS5
                    else -> continue
                }

                val anonymity = item.optString("anonymity").lowercase()
                if (anonymity == "transparent") continue
                val host = item.optString("ip").trim()
                val port = item.optInt("port", 0)
                if (host.isBlank() || port !in 1..65535) continue

                add(
                    JoynPublicProxyCandidate(
                        host = host,
                        port = port,
                        transport = transport,
                        source = "Proxifly",
                        anonymity = anonymity,
                        uptimePercent = item.optDouble("score", 0.0) * 100.0,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun rankCandidates(
        candidates: List<JoynPublicProxyCandidate>,
    ): List<JoynPublicProxyCandidate> = candidates.sortedWith(
        compareByDescending<JoynPublicProxyCandidate> { if (it.anonymity == "elite") 1 else 0 }
            .thenByDescending { it.uptimePercent }
            .thenByDescending { if (it.transport == JoynProxyTransport.HTTP) 1 else 0 }
            .thenBy { it.reportedLatencyMs }
            .thenBy { it.host }
            .thenBy { it.port },
    )

    private fun probe(
        country: JoynCountry,
        candidate: JoynPublicProxyCandidate,
        apiKey: String,
    ): ProbedProxy? {
        val javaProxyType = when (candidate.transport) {
            JoynProxyTransport.HTTP -> Proxy.Type.HTTP
            JoynProxyTransport.SOCKS5 -> Proxy.Type.SOCKS
        }
        val proxy = Proxy(
            javaProxyType,
            InetSocketAddress.createUnresolved(candidate.host, candidate.port),
        )
        val client = directClient.newBuilder()
            .proxy(proxy)
            .connectTimeout(PROBE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val actualCountry = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.cloudflare.com/cdn-cgi/trace")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body.string()
                    .lineSequence()
                    .firstOrNull { it.startsWith("loc=") }
                    ?.substringAfter("loc=")
                    ?.trim()
                    ?.uppercase()
            }
        }.getOrNull() ?: return null
        if (actualCountry != country.name) return null

        val startedNs = System.nanoTime()
        val joynWorks = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build(),
            ).execute().use { response ->
                response.code in 200..399
            }
        }.getOrDefault(false)
        if (!joynWorks) return null

        val token = createAnonymousProbeToken(client, country) ?: return null
        val channelId = loadFreeLiveChannelId(client, country, apiKey, token) ?: return null
        if (!hasLiveEntitlement(client, channelId, token)) return null

        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        return ProbedProxy(candidate, latencyMs)
    }

    private fun createAnonymousProbeToken(
        client: OkHttpClient,
        country: JoynCountry,
    ): ProbeToken? = runCatching {
        val payload = JSONObject()
            .put("anon_device_id", UUID.randomUUID().toString())
            .put("client_id", UUID.randomUUID().toString())
            .put("client_name", "web")
        client.newCall(
            Request.Builder()
                .url("${JoynProtocol.authBaseUrl}/anonymous")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return@runCatching null
            val json = JSONObject(body)
            val accessToken = json.optString("access_token").takeIf(String::isNotBlank)
                ?: return@runCatching null
            ProbeToken(
                accessToken = accessToken,
                tokenType = json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer",
            )
        }
    }.getOrNull()

    private fun loadFreeLiveChannelId(
        client: OkHttpClient,
        country: JoynCountry,
        apiKey: String,
        token: ProbeToken,
    ): String? = runCatching {
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", LIVE_PROBE_QUERY)
            .build()
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.graphqlTenant)
                .header("Authorization", "${token.tokenType} ${token.accessToken}")
                .get()
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return@runCatching null
            val json = JSONObject(body)
            if ((json.optJSONArray("errors")?.length() ?: 0) > 0) return@runCatching null
            val streams = json.optJSONObject("data")?.optJSONArray("liveStreams")
                ?: return@runCatching null
            for (index in 0 until streams.length()) {
                val stream = streams.optJSONObject(index) ?: continue
                val markings = stream.optJSONArray("markings")
                var paid = false
                if (markings != null) {
                    for (markingIndex in 0 until markings.length()) {
                        val marking = markings.optString(markingIndex)
                        if (marking == "PLUS" || marking == "PREMIUM") {
                            paid = true
                            break
                        }
                    }
                }
                if (!paid) {
                    stream.optString("id").takeIf(String::isNotBlank)?.let { return@runCatching it }
                }
            }
            null
        }
    }.getOrNull()

    private fun hasLiveEntitlement(
        client: OkHttpClient,
        channelId: String,
        token: ProbeToken,
    ): Boolean = runCatching {
        val payload = JSONObject()
            .put("content_id", channelId)
            .put("content_type", "LIVE")
        client.newCall(
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "${token.tokenType} ${token.accessToken}")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return@runCatching false
            JSONObject(body).optString("entitlement_token").isNotBlank()
        }
    }.getOrDefault(false)

    private data class ProbeToken(
        val accessToken: String,
        val tokenType: String,
    )

    private data class ProbedProxy(
        val candidate: JoynPublicProxyCandidate,
        val latencyMs: Long,
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MIN_PRIMARY_CANDIDATES = 12
        private const val MAX_CANDIDATES = 18
        private const val PARALLELISM = 6
        private const val PROBE_CONNECT_TIMEOUT_SECONDS = 4L
        private const val PROBE_READ_TIMEOUT_SECONDS = 6L
        private const val PROBE_CALL_TIMEOUT_SECONDS = 12L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query ProxyLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
