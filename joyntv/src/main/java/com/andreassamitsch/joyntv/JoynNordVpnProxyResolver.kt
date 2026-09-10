package com.andreassamitsch.joyntv

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers NordVPN servers for the selected Joyn country and checks whether a server still
 * exposes a usable authenticated proxy endpoint. NordVPN's historical HTTPS/CONNECT proxy on
 * port 89 is tested first. SOCKS5 on port 1080 is tested as an additional compatibility path.
 *
 * A candidate is only accepted after the same checks that matter for the app itself: exit country,
 * Joyn HTTPS, anonymous auth, live GraphQL and a LIVE entitlement. This prevents a technically
 * reachable Nord endpoint from being selected when Joyn rejects its exit IP as VPN/proxy.
 */
internal class JoynNordVpnProxyResolver {
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
        username: String,
        password: String,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult {
        if (username.isBlank() || password.isBlank()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Bitte NordVPN-Service-Benutzername und Service-Passwort eingeben.",
            )
        }
        if (apiKey.isNullOrBlank()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Der Joyn API-Schlüssel für ${country.name} fehlt. Joyn bitte einmal ohne Proxy starten und danach erneut testen.",
            )
        }

        onProgress(JoynProxyDiscoveryProgress("Lade NordVPN-Server für ${country.name} …"))
        val servers = loadRecommendedServers(country)
        if (servers.isEmpty()) {
            return JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "NordVPN lieferte aktuell keine Server für ${country.name}.",
            )
        }

        val candidates = buildList {
            // The user's previously working Nord configuration used authenticated HTTP CONNECT/89.
            servers.forEach { add(NordCandidate(it.hostname, 89, JoynProxyTransport.HTTP, it.load)) }
            // Nord currently documents SOCKS5 on 1080. Not every generic country server exposes it,
            // therefore every endpoint is probed instead of assuming support from the hostname.
            servers.forEach { add(NordCandidate(it.hostname, 1080, JoynProxyTransport.SOCKS5, it.load)) }
        }

        var attempted = 0
        var wrongCountry = 0
        var joynBlocked = 0
        var unreachable = 0

        for (candidate in candidates) {
            attempted++
            val protocolLabel = when (candidate.transport) {
                JoynProxyTransport.HTTP -> "HTTPS/CONNECT :${candidate.port}"
                JoynProxyTransport.SOCKS5 -> "SOCKS5 :${candidate.port}"
            }
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Teste NordVPN $attempted/${candidates.size}: ${candidate.host} · $protocolLabel · Joyn Live …",
                    attempted = attempted - 1,
                    total = candidates.size,
                ),
            )

            when (val result = probe(country, candidate, apiKey, username, password)) {
                is NordProbeResult.Success -> {
                    val source = when (candidate.transport) {
                        JoynProxyTransport.HTTP -> "NordVPN · HTTPS/CONNECT 89"
                        JoynProxyTransport.SOCKS5 -> "NordVPN · SOCKS5 1080"
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
                    val message = "Joyn-tauglicher NordVPN-Server: ${candidate.host}:${candidate.port} · ${candidate.transport.name} · ${result.latencyMs} ms"
                    onProgress(JoynProxyDiscoveryProgress(message, attempted, candidates.size))
                    return JoynProxyDiscoveryResult(config, candidates.size, attempted, message)
                }
                NordProbeResult.WrongCountry -> wrongCountry++
                NordProbeResult.JoynBlocked -> joynBlocked++
                NordProbeResult.Unreachable -> unreachable++
            }
        }

        return JoynProxyDiscoveryResult(
            config = null,
            candidates = candidates.size,
            attempted = attempted,
            message = "Kein NordVPN-Proxy für ${country.name} bestand die Joyn-Live-Prüfung. " +
                "Getestet: $attempted · nicht erreichbar/Auth: $unreachable · falsches Exit-Land: $wrongCountry · Joyn blockiert/keine Live-Freigabe: $joynBlocked.",
        )
    }

    private fun loadRecommendedServers(country: JoynCountry): List<NordServer> {
        val countriesBody = executeDirect("https://api.nordvpn.com/v1/servers/countries") ?: return emptyList()
        val countries = runCatching { JSONArray(countriesBody) }.getOrNull() ?: return emptyList()
        val expectedName = when (country) {
            JoynCountry.DE -> "Germany"
            JoynCountry.AT -> "Austria"
            JoynCountry.CH -> "Switzerland"
        }
        var countryId: Long? = null
        for (index in 0 until countries.length()) {
            val item = countries.optJSONObject(index) ?: continue
            val matches = item.optString("code").equals(country.name, ignoreCase = true) ||
                item.optString("name").equals(expectedName, ignoreCase = true)
            if (matches) {
                countryId = item.optLong("id").takeIf { it > 0L }
                break
            }
        }
        val id = countryId ?: return emptyList()
        val url = "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter("limit", MAX_SERVERS.toString())
            .addQueryParameter("filters[country_id]", id.toString())
            .build()
        val body = executeDirect(url.toString()) ?: return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hostname = item.optString("hostname").trim()
                if (hostname.isBlank()) continue
                if (item.optString("status").isNotBlank() && !item.optString("status").equals("online", true)) continue
                add(NordServer(hostname, item.optInt("load", 100)))
            }
        }.distinctBy { it.hostname }.sortedBy { it.load }.take(MAX_SERVERS)
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
        return if (candidate.transport == JoynProxyTransport.SOCKS5) {
            synchronized(SOCKS_AUTH_LOCK) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? =
                        if (requestorType == RequestorType.PROXY) {
                            PasswordAuthentication(username, password.toCharArray())
                        } else null
                })
                try {
                    probeWithClient(country, candidate, apiKey, username, password)
                } finally {
                    // Repository reinstalls the previously persisted proxy configuration after
                    // Nord discovery. Null here prevents test credentials from leaking process-wide.
                    Authenticator.setDefault(null)
                }
            }
        } else {
            probeWithClient(country, candidate, apiKey, username, password)
        }
    }

    private fun probeWithClient(
        country: JoynCountry,
        candidate: NordCandidate,
        apiKey: String,
        username: String,
        password: String,
    ): NordProbeResult {
        val proxyType = when (candidate.transport) {
            JoynProxyTransport.HTTP -> Proxy.Type.HTTP
            JoynProxyTransport.SOCKS5 -> Proxy.Type.SOCKS
        }
        val proxy = Proxy(proxyType, InetSocketAddress.createUnresolved(candidate.host, candidate.port))
        val builder = directClient.newBuilder()
            .proxy(proxy)
            .connectTimeout(PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)

        if (candidate.transport == JoynProxyTransport.HTTP) {
            val credential = Credentials.basic(username, password)
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
        }
        val client = builder.build()

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
            response.isSuccessful && runCatching { JSONObject(body).optString("entitlement_token").isNotBlank() }.getOrDefault(false)
        }
    }.getOrDefault(false)

    private data class NordServer(val hostname: String, val load: Int)
    private data class NordCandidate(
        val host: String,
        val port: Int,
        val transport: JoynProxyTransport,
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
        private const val MAX_SERVERS = 18
        private const val PROBE_CONNECT_SECONDS = 3L
        private const val PROBE_READ_SECONDS = 6L
        private const val PROBE_CALL_SECONDS = 10L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query NordProxyLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
