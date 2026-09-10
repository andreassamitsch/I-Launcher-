package com.andreassamitsch.joyntv

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers NordVPN endpoints that explicitly advertise proxy capability for the selected country.
 * Nord's proxy_ssl technology uses a TLS connection to the proxy itself on port 89; SOCKS5 uses
 * port 1080. A candidate is only accepted after exit-country, Joyn API and LIVE entitlement checks.
 */
internal class JoynNordVpnProxyResolver {
    private val directClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun findBest(
        country: JoynCountry,
        allTraffic: Boolean,
        apiKey: String?,
        username: String,
        password: String,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        if (username.isBlank() || password.isBlank()) {
            return JoynProxyDiscoveryResult(null, 0, 0, "Bitte NordVPN-Service-Benutzername und Service-Passwort eingeben.")
        }
        if (apiKey.isNullOrBlank()) {
            return JoynProxyDiscoveryResult(
                null,
                0,
                0,
                "Der Joyn API-Schlüssel für ${country.name} fehlt. Joyn bitte einmal ohne Proxy starten und danach erneut testen.",
            )
        }

        onProgress(JoynProxyDiscoveryProgress("Lade proxyfähige NordVPN-Server für ${country.name} …"))
        val candidates = loadCandidates(country)
        if (candidates.isEmpty()) {
            return JoynProxyDiscoveryResult(
                null,
                0,
                0,
                "NordVPN-API lieferte keine proxyfähigen Server für ${country.name}. Normale VPN-Server werden dabei bewusst nicht als Proxy behandelt.",
            )
        }

        val httpsCount = candidates.count { it.tlsProxy }
        val socksCount = candidates.count { it.transport == JoynProxyTransport.SOCKS5 }
        onProgress(
            JoynProxyDiscoveryProgress(
                "NordVPN: $httpsCount HTTPS/89 + $socksCount SOCKS5/1080 Kandidaten für ${country.name}. Starte Joyn-Prüfung …",
                total = candidates.size,
            ),
        )

        var attempted = 0
        var wrongCountry = 0
        var joynBlocked = 0
        var unreachable = 0

        for (candidate in candidates) {
            attempted++
            val protocolLabel = if (candidate.tlsProxy) "HTTPS-Proxy :89" else "SOCKS5 :1080"
            onProgress(
                JoynProxyDiscoveryProgress(
                    "Teste $attempted/${candidates.size}: ${candidate.host} · $protocolLabel · Joyn Live …",
                    attempted - 1,
                    candidates.size,
                ),
            )

            when (val result = probe(country, candidate, apiKey, username, password)) {
                is NordProbeResult.Success -> {
                    val source = if (candidate.tlsProxy) {
                        "${JoynProxyConfig.NORD_TLS_SOURCE_PREFIX} 89"
                    } else {
                        "NordVPN · SOCKS5 1080"
                    }
                    val config = JoynProxyConfig(
                        enabled = true,
                        automatic = true,
                        transport = candidate.transport,
                        host = candidate.host,
                        port = candidate.port,
                        username = username,
                        password = password,
                        allTraffic = allTraffic,
                        source = source,
                        latencyMs = result.latencyMs,
                        lastVerifiedAtEpochMs = System.currentTimeMillis(),
                    )
                    val message = "Joyn-tauglicher NordVPN-Server: ${candidate.host}:${candidate.port} · $protocolLabel · ${result.latencyMs} ms"
                    onProgress(JoynProxyDiscoveryProgress(message, attempted, candidates.size))
                    return JoynProxyDiscoveryResult(config, candidates.size, attempted, message)
                }
                NordProbeResult.WrongCountry -> wrongCountry++
                NordProbeResult.JoynBlocked -> joynBlocked++
                NordProbeResult.Unreachable -> unreachable++
            }
        }

        return JoynProxyDiscoveryResult(
            null,
            candidates.size,
            attempted,
            "Kein NordVPN-Proxy für ${country.name} bestand die Joyn-Live-Prüfung. " +
                "Getestet: $attempted · nicht erreichbar/Auth: $unreachable · falsches Exit-Land: $wrongCountry · " +
                "Joyn blockiert/keine Live-Freigabe: $joynBlocked.",
        )
    }

    private fun loadCandidates(country: JoynCountry): List<NordCandidate> {
        val tlsServers = (
            loadTechnologyServers(country, "proxy_ssl") +
                loadTechnologyServers(country, "proxy_ssl_cybersec")
            )
            .distinctBy { it.hostname }
            .sortedBy { it.load }
            .take(MAX_HTTPS_SERVERS)
            .map {
                NordCandidate(
                    host = it.hostname,
                    port = 89,
                    transport = JoynProxyTransport.HTTP,
                    tlsProxy = true,
                    load = it.load,
                )
            }

        val socksServers = loadTechnologyServers(country, "socks")
            .distinctBy { it.hostname }
            .sortedBy { it.load }
            .take(MAX_SOCKS_SERVERS)
            .map {
                NordCandidate(
                    host = it.hostname,
                    port = 1080,
                    transport = JoynProxyTransport.SOCKS5,
                    tlsProxy = false,
                    load = it.load,
                )
            }

        return tlsServers + socksServers
    }

    private fun loadTechnologyServers(country: JoynCountry, technology: String): List<NordServer> {
        val countryId = when (country) {
            JoynCountry.DE -> 81
            JoynCountry.AT -> 14
            JoynCountry.CH -> 209
        }
        val expectedName = when (country) {
            JoynCountry.DE -> "Germany"
            JoynCountry.AT -> "Austria"
            JoynCountry.CH -> "Switzerland"
        }

        val endpoints = listOf(
            "https://api.nordvpn.com/v1/servers".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "0")
                .addQueryParameter("filters[country_id]", countryId.toString())
                .addQueryParameter("filters[servers_technologies][identifier]", technology)
                .build(),
            "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
                .addQueryParameter("limit", SERVER_FETCH_LIMIT.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .addQueryParameter("filters[servers_technologies][identifier]", technology)
                .build(),
        )

        for (url in endpoints) {
            val body = executeDirect(url.toString()) ?: continue
            val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val hostname = item.optString("hostname").trim()
                    if (hostname.isBlank()) continue
                    val status = item.optString("status")
                    if (status.isNotBlank() && !status.equals("online", true)) continue
                    if (!belongsToCountry(item, country, expectedName, hostname)) continue
                    add(NordServer(hostname, item.optInt("load", 100)))
                }
            }.distinctBy { it.hostname }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun belongsToCountry(
        item: JSONObject,
        country: JoynCountry,
        expectedName: String,
        hostname: String,
    ): Boolean {
        val locations = item.optJSONArray("locations")
        if (locations != null) {
            for (index in 0 until locations.length()) {
                val countryJson = locations.optJSONObject(index)?.optJSONObject("country") ?: continue
                val code = countryJson.optString("code")
                val name = countryJson.optString("name")
                if (code.equals(country.name, true) || name.equals(expectedName, true)) return true
            }
        }
        return hostname.lowercase().startsWith(country.name.lowercase())
    }

    private fun executeDirect(url: String): String? = runCatching {
        directClient.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body.string()
        }
    }.getOrNull()

    private fun probe(
        country: JoynCountry,
        candidate: NordCandidate,
        apiKey: String,
        username: String,
        password: String,
    ): NordProbeResult {
        if (candidate.tlsProxy) {
            return runCatching {
                JoynTlsProxyBridge(candidate.host, candidate.port, username, password).use { bridge ->
                    val proxy = Proxy(Proxy.Type.HTTP, bridge.localAddress)
                    probeWithProxy(country, candidate, apiKey, proxy)
                }
            }.getOrDefault(NordProbeResult.Unreachable)
        }

        return synchronized(SOCKS_AUTH_LOCK) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) {
                        PasswordAuthentication(username, password.toCharArray())
                    } else null
            })
            try {
                val proxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(candidate.host, candidate.port),
                )
                probeWithProxy(country, candidate, apiKey, proxy)
            } finally {
                Authenticator.setDefault(null)
            }
        }
    }

    private fun probeWithProxy(
        country: JoynCountry,
        candidate: NordCandidate,
        apiKey: String,
        proxy: Proxy,
    ): NordProbeResult {
        val client = directClient.newBuilder()
            .proxy(proxy)
            .connectTimeout(PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)
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
                response.body.string().lineSequence()
                    .firstOrNull { it.startsWith("loc=") }
                    ?.substringAfter("loc=")?.trim()?.uppercase()
            }
        }.getOrNull() ?: return NordProbeResult.Unreachable
        if (actualCountry != country.name) return NordProbeResult.WrongCountry

        val startedNs = System.nanoTime()
        val joynReachable = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get().build(),
            ).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        if (!joynReachable) return NordProbeResult.Unreachable

        val token = createAnonymousToken(client, country) ?: return NordProbeResult.JoynBlocked
        val channelId = loadFreeLiveChannelId(client, country, apiKey, token) ?: return NordProbeResult.JoynBlocked
        if (!hasLiveEntitlement(client, channelId, token)) return NordProbeResult.JoynBlocked

        val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        return NordProbeResult.Success(latency)
    }

    private fun createAnonymousToken(client: OkHttpClient, country: JoynCountry): ProbeToken? = runCatching {
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
            val accessToken = json.optString("access_token").takeIf(String::isNotBlank) ?: return@runCatching null
            ProbeToken(accessToken, json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer")
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
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.graphqlTenant)
                .header("Authorization", "${token.tokenType} ${token.accessToken}")
                .get().build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return@runCatching null
            val json = JSONObject(body)
            if ((json.optJSONArray("errors")?.length() ?: 0) > 0) return@runCatching null
            val streams = json.optJSONObject("data")?.optJSONArray("liveStreams") ?: return@runCatching null
            for (index in 0 until streams.length()) {
                val stream = streams.optJSONObject(index) ?: continue
                val markings = stream.optJSONArray("markings")
                var paid = false
                if (markings != null) {
                    for (m in 0 until markings.length()) {
                        if (markings.optString(m) in setOf("PLUS", "PREMIUM")) {
                            paid = true
                            break
                        }
                    }
                }
                if (!paid) stream.optString("id").takeIf(String::isNotBlank)?.let { return@runCatching it }
            }
            null
        }
    }.getOrNull()

    private fun hasLiveEntitlement(client: OkHttpClient, channelId: String, token: ProbeToken): Boolean = runCatching {
        val payload = JSONObject().put("content_id", channelId).put("content_type", "LIVE")
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
            response.isSuccessful && runCatching {
                JSONObject(body).optString("entitlement_token").isNotBlank()
            }.getOrDefault(false)
        }
    }.getOrDefault(false)

    private data class NordServer(val hostname: String, val load: Int)
    private data class NordCandidate(
        val host: String,
        val port: Int,
        val transport: JoynProxyTransport,
        val tlsProxy: Boolean,
        val load: Int,
    )
    private data class ProbeToken(val accessToken: String, val tokenType: String)

    private sealed interface NordProbeResult {
        data class Success(val latencyMs: Long) : NordProbeResult
        data object WrongCountry : NordProbeResult
        data object JoynBlocked : NordProbeResult
        data object Unreachable : NordProbeResult
    }

    private companion object {
        private val SOCKS_AUTH_LOCK = Any()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SERVER_FETCH_LIMIT = 100
        private const val MAX_HTTPS_SERVERS = 30
        private const val MAX_SOCKS_SERVERS = 15
        private const val PROBE_CONNECT_SECONDS = 4L
        private const val PROBE_READ_SECONDS = 8L
        private const val PROBE_CALL_SECONDS = 14L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query ProxyLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
