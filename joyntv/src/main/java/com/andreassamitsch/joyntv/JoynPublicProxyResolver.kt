package com.andreassamitsch.joyntv

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

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
    val source: String,
    val anonymity: String = "",
    val uptimePercent: Double = 0.0,
    val reportedLatencyMs: Double = Double.MAX_VALUE,
)

/**
 * Test-only automatic proxy discovery.
 *
 * Public lists are downloaded over the direct connection and are never trusted on their own:
 * every candidate must prove the expected exit country and must establish HTTPS to Joyn before
 * it can be persisted as the active proxy.
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
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        onProgress(JoynProxyDiscoveryProgress("Lade aktuelle ProxyScrape-Liste für ${country.name} …"))
        val primary = fetchProxyScrape(country)

        val fallback = if (primary.size < MIN_PRIMARY_CANDIDATES) {
            onProgress(JoynProxyDiscoveryProgress("Ergänze Kandidaten aus einer zweiten öffentlichen Liste …"))
            fetchProxifly(country)
        } else {
            emptyList()
        }

        val candidates = rankCandidates(primary + fallback)
            .distinctBy { "${it.host}:${it.port}" }
            .take(MAX_CANDIDATES)

        if (candidates.isEmpty()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Für ${country.name} wurden aktuell keine technisch passenden HTTP(S)-Proxys gefunden.",
            )
        }

        var attempted = 0
        for (batch in candidates.chunked(PARALLELISM)) {
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Teste ${attempted + 1}–${attempted + batch.size} von ${candidates.size}: Land und Joyn-HTTPS …",
                    attempted = attempted,
                    total = candidates.size,
                ),
            )

            val working = coroutineScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) { probe(country, candidate) }
                }.awaitAll().filterNotNull()
            }
            attempted += batch.size

            val best = working.minByOrNull { it.latencyMs }
            if (best != null) {
                val config = JoynProxyConfig(
                    enabled = true,
                    automatic = true,
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
                    "Funktionierender ${country.name}-Proxy: ${config.host}:${config.port} · ${config.latencyMs} ms · ${config.source}"
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
            message = "Keiner der ${attempted} getesteten ${country.name}-Proxys konnte Land und Joyn-HTTPS erfolgreich bestätigen.",
        )
    }

    private suspend fun fetchProxyScrape(country: JoynCountry): List<JoynPublicProxyCandidate> {
        val countryCode = country.name.lowercase()
        val countryUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/$countryCode/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$countryCode/data.json",
        )
        val countryBody = fetchFirst(countryUrls)
        val countryCandidates = countryBody
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
        if (countryCandidates.isNotEmpty()) return countryCandidates

        val globalUrls = listOf(
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/all/data.json",
            "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/all/data.json",
        )
        return fetchFirst(globalUrls)
            ?.let { parseProxyScrape(it, country) }
            .orEmpty()
    }

    private suspend fun fetchProxifly(country: JoynCountry): List<JoynPublicProxyCandidate> {
        val urls = listOf(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.json",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/all/data.json",
        )
        return fetchFirst(urls)
            ?.let { parseProxifly(it, country) }
            .orEmpty()
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
                if (protocol != "http" && protocol != "https") continue
                val supportsHttps = item.optBoolean("ssl", false) || protocol == "https"
                if (!supportsHttps) continue

                val anonymity = item.optString("anonymity").lowercase()
                if (anonymity == "transparent") continue
                val host = item.optString("ip").trim()
                val port = item.optInt("port", 0)
                if (host.isBlank() || port !in 1..65535) continue

                add(
                    JoynPublicProxyCandidate(
                        host = host,
                        port = port,
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
                if (protocol != "http" && protocol != "https") continue
                val supportsHttps = item.optBoolean("https", false) || protocol == "https"
                if (!supportsHttps) continue

                val anonymity = item.optString("anonymity").lowercase()
                if (anonymity == "transparent") continue
                val host = item.optString("ip").trim()
                val port = item.optInt("port", 0)
                if (host.isBlank() || port !in 1..65535) continue

                add(
                    JoynPublicProxyCandidate(
                        host = host,
                        port = port,
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
        compareByDescending<JoynPublicProxyCandidate> { it.anonymity == "elite" }
            .thenByDescending { it.uptimePercent }
            .thenBy { it.reportedLatencyMs }
            .thenBy { it.host }
            .thenBy { it.port },
    )

    private fun probe(
        country: JoynCountry,
        candidate: JoynPublicProxyCandidate,
    ): ProbedProxy? {
        val proxy = Proxy(
            Proxy.Type.HTTP,
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

        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        return ProbedProxy(candidate, latencyMs)
    }

    private data class ProbedProxy(
        val candidate: JoynPublicProxyCandidate,
        val latencyMs: Long,
    )

    private companion object {
        private const val MIN_PRIMARY_CANDIDATES = 12
        private const val MAX_CANDIDATES = 24
        private const val PARALLELISM = 6
        private const val PROBE_CONNECT_TIMEOUT_SECONDS = 3L
        private const val PROBE_READ_TIMEOUT_SECONDS = 4L
        private const val PROBE_CALL_TIMEOUT_SECONDS = 6L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 Chrome/140 Safari/537.36"
    }
}
