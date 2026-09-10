package com.andreassamitsch.joyntv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers NordVPN proxy endpoints for the selected Joyn country and proves them end-to-end.
 *
 * Important: Nord v2 separates server, location and technology metadata. For proxy discovery we
 * therefore prefer the v1/recommendations endpoints with an explicit technology filter. Only if
 * those return too few endpoints do we add ordinary country servers as a compatibility fallback.
 * Port 89 gets an explicit TCP -> TLS -> CONNECT/Auth preflight before any Joyn request is made.
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

        onProgress(JoynProxyDiscoveryProgress("Lade NordVPN-Proxyserver für ${country.name} …"))
        val discovery = loadCandidates(country)
        val candidates = discovery.candidates
        if (candidates.isEmpty()) {
            return JoynProxyDiscoveryResult(
                null,
                0,
                0,
                "NordVPN lieferte Server für ${country.name}, aber daraus konnten keine Proxy-Kandidaten erzeugt werden. ${discovery.summary}",
            )
        }

        onProgress(
            JoynProxyDiscoveryProgress(
                "NordVPN ${country.name}: ${discovery.summary}. Starte technische Proxy-Prüfung …",
                total = candidates.size,
            ),
        )

        var attempted = 0
        val failures = linkedMapOf<String, Int>()
        val examples = mutableListOf<String>()

        fun recordFailure(candidate: NordCandidate, stage: String, detail: String) {
            failures[stage] = (failures[stage] ?: 0) + 1
            if (examples.size < MAX_FAILURE_EXAMPLES) {
                examples += "${candidate.host}:${candidate.port} [$stage] ${detail.take(120)}"
            }
        }

        for (candidate in candidates) {
            attempted++
            val protocolLabel = if (candidate.tlsProxy) "HTTPS :89" else "SOCKS5 :1080"
            val capabilityLabel = if (candidate.advertised) "Nord-markiert" else "Fallback"
            onProgress(
                JoynProxyDiscoveryProgress(
                    "Teste $attempted/${candidates.size}: ${candidate.host} · $protocolLabel · $capabilityLabel …",
                    attempted - 1,
                    candidates.size,
                ),
            )

            if (candidate.tlsProxy) {
                val preflight = preflightTlsProxy(candidate, username, password)
                if (!preflight.ok) {
                    recordFailure(candidate, preflight.stage, preflight.detail)
                    onProgress(
                        JoynProxyDiscoveryProgress(
                            "${candidate.host}: ${preflight.stage} fehlgeschlagen · ${preflight.detail}",
                            attempted,
                            candidates.size,
                        ),
                    )
                    continue
                }
            }

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
                    val message =
                        "Joyn-tauglicher NordVPN-Server: ${candidate.host}:${candidate.port} · $protocolLabel · ${result.latencyMs} ms"
                    onProgress(JoynProxyDiscoveryProgress(message, attempted, candidates.size))
                    return JoynProxyDiscoveryResult(config, candidates.size, attempted, message)
                }
                is NordProbeResult.Failed -> {
                    recordFailure(candidate, result.stage, result.detail)
                    onProgress(
                        JoynProxyDiscoveryProgress(
                            "${candidate.host}: ${result.stage} fehlgeschlagen · ${result.detail}",
                            attempted,
                            candidates.size,
                        ),
                    )
                }
            }
        }

        val failureSummary = if (failures.isEmpty()) {
            "keine Fehlerdetails"
        } else {
            failures.entries.joinToString(" · ") { (stage, count) -> "$stage=$count" }
        }
        val exampleSummary = if (examples.isEmpty()) "" else "\nBeispiele:\n" + examples.joinToString("\n")

        return JoynProxyDiscoveryResult(
            null,
            candidates.size,
            attempted,
            "Kein NordVPN-Proxy für ${country.name} bestand die Prüfung. Getestet: $attempted. " +
                "$failureSummary.$exampleSummary\nServerauswahl: ${discovery.summary}",
        )
    }

    private fun loadCandidates(country: JoynCountry): NordDiscovery {
        val advertisedTls = (
            loadTechnologyServers(country, "proxy_ssl") +
                loadTechnologyServers(country, "proxy_ssl_cybersec")
            )
            .distinctBy { it.hostname }
            .sortedBy { it.load }

        val advertisedSocks = loadTechnologyServers(country, "socks")
            .distinctBy { it.hostname }
            .sortedBy { it.load }

        val ordinary = loadCountryServersV1(country)
            .distinctBy { it.hostname }
            .sortedBy { it.load }

        val tls = buildList {
            advertisedTls.take(MAX_HTTPS_SERVERS).forEach { server ->
                add(server.toCandidate(89, JoynProxyTransport.HTTP, tlsProxy = true, advertised = true))
            }
            if (size < MAX_HTTPS_SERVERS) {
                ordinary.asSequence()
                    .filter { server -> advertisedTls.none { it.hostname == server.hostname } }
                    .take(MAX_HTTPS_SERVERS - size)
                    .forEach { server ->
                        add(server.toCandidate(89, JoynProxyTransport.HTTP, tlsProxy = true, advertised = false))
                    }
            }
        }

        val socks = buildList {
            advertisedSocks.take(MAX_SOCKS_SERVERS).forEach { server ->
                add(server.toCandidate(1080, JoynProxyTransport.SOCKS5, tlsProxy = false, advertised = true))
            }
            if (size < MAX_SOCKS_SERVERS) {
                ordinary.asSequence()
                    .filter { server -> advertisedSocks.none { it.hostname == server.hostname } }
                    .take(MAX_SOCKS_SERVERS - size)
                    .forEach { server ->
                        add(server.toCandidate(1080, JoynProxyTransport.SOCKS5, tlsProxy = false, advertised = false))
                    }
            }
        }

        val summary =
            "HTTPS/89 ${tls.size} (${tls.count { it.advertised }} als proxy_ssl markiert), " +
                "SOCKS5/1080 ${socks.size} (${socks.count { it.advertised }} als socks markiert), " +
                "normale Landesliste ${ordinary.size}"
        return NordDiscovery(tls + socks, summary)
    }

    private fun NordServer.toCandidate(
        port: Int,
        transport: JoynProxyTransport,
        tlsProxy: Boolean,
        advertised: Boolean,
    ) = NordCandidate(
        host = hostname,
        port = port,
        transport = transport,
        tlsProxy = tlsProxy,
        load = load,
        advertised = advertised,
    )

    private fun loadTechnologyServers(country: JoynCountry, technology: String): List<NordServer> {
        val countryId = countryId(country)
        val expectedName = expectedCountryName(country)
        val endpoints = listOf(
            "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
                .addQueryParameter("limit", RECOMMENDATION_FETCH_LIMIT.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .addQueryParameter("filters[servers_technologies][identifier]", technology)
                .build(),
            "https://api.nordvpn.com/v1/servers".toHttpUrl().newBuilder()
                .addQueryParameter("limit", SERVER_FETCH_LIMIT.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .addQueryParameter("filters[servers_technologies][identifier]", technology)
                .build(),
        )
        for (url in endpoints) {
            val body = executeDirect(url.toString()) ?: continue
            val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val parsed = parseV1Servers(array, country, expectedName)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun loadCountryServersV1(country: JoynCountry): List<NordServer> {
        val countryId = countryId(country)
        val expectedName = expectedCountryName(country)
        val endpoints = listOf(
            "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
                .addQueryParameter("limit", RECOMMENDATION_FETCH_LIMIT.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .build(),
            "https://api.nordvpn.com/v1/servers".toHttpUrl().newBuilder()
                .addQueryParameter("limit", SERVER_FETCH_LIMIT.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .build(),
        )
        for (url in endpoints) {
            val body = executeDirect(url.toString()) ?: continue
            val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val parsed = parseV1Servers(array, country, expectedName)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseV1Servers(
        array: JSONArray,
        country: JoynCountry,
        expectedName: String,
    ): List<NordServer> = buildList {
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

    private fun countryId(country: JoynCountry): Int = when (country) {
        JoynCountry.DE -> 81
        JoynCountry.AT -> 14
        JoynCountry.CH -> 209
    }

    private fun expectedCountryName(country: JoynCountry): String = when (country) {
        JoynCountry.DE -> "Germany"
        JoynCountry.AT -> "Austria"
        JoynCountry.CH -> "Switzerland"
    }

    private fun belongsToCountry(
        item: JSONObject,
        country: JoynCountry,
        expectedName: String,
        hostname: String,
    ): Boolean {
        if (hostname.lowercase().startsWith(country.name.lowercase())) return true
        val locations = item.optJSONArray("locations") ?: return false
        for (index in 0 until locations.length()) {
            val countryJson = locations.optJSONObject(index)?.optJSONObject("country") ?: continue
            val code = countryJson.optString("code")
            val name = countryJson.optString("name")
            if (code.equals(country.name, true) || name.equals(expectedName, true)) return true
        }
        return false
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

    private fun preflightTlsProxy(
        candidate: NordCandidate,
        username: String,
        password: String,
    ): ProxyPreflight {
        var tls: SSLSocket? = null
        return try {
            val tlsConnection = JoynNordProxyTls.connect(
                host = candidate.host,
                port = candidate.port,
                connectTimeoutMs = PREFLIGHT_CONNECT_TIMEOUT_MS,
                readTimeoutMs = PREFLIGHT_READ_TIMEOUT_MS,
            )
            tls = tlsConnection.socket

            val credentials = Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray(StandardCharsets.ISO_8859_1),
            )
            val request = buildString {
                append("CONNECT www.cloudflare.com:443 HTTP/1.1\r\n")
                append("Host: www.cloudflare.com:443\r\n")
                append("Proxy-Authorization: Basic $credentials\r\n")
                append("Proxy-Connection: Keep-Alive\r\n")
                append("Connection: Keep-Alive\r\n\r\n")
            }
            tls.outputStream.write(request.toByteArray(StandardCharsets.ISO_8859_1))
            tls.outputStream.flush()

            val reader = BufferedReader(InputStreamReader(tls.inputStream, StandardCharsets.ISO_8859_1))
            val statusLine = reader.readLine().orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            when {
                statusLine.contains(" 200 ") -> ProxyPreflight(
                    true,
                    "CONNECT",
                    "HTTP 200 · ${tlsConnection.certificateSummary.take(110)}",
                )
                statusLine.contains(" 407 ") -> ProxyPreflight(false, "AUTH", statusLine.ifBlank { "HTTP 407" })
                statusLine.isBlank() -> ProxyPreflight(false, "CONNECT", "keine Antwort nach TLS-Handshake")
                else -> ProxyPreflight(false, "CONNECT", statusLine)
            }
        } catch (error: Throwable) {
            when (error) {
                is UnknownHostException -> ProxyPreflight(false, "DNS", summarize(error))
                is ConnectException -> ProxyPreflight(false, "TCP", summarize(error))
                is SocketTimeoutException -> ProxyPreflight(false, "TIMEOUT", summarize(error))
                is SSLHandshakeException -> ProxyPreflight(false, "TLS", summarize(error))
                else -> ProxyPreflight(false, "TLS/CONNECT", summarize(error))
            }
        } finally {
            runCatching { tls?.close() }
        }
    }

    private fun probe(
        country: JoynCountry,
        candidate: NordCandidate,
        apiKey: String,
        username: String,
        password: String,
    ): NordProbeResult {
        if (candidate.tlsProxy) {
            return try {
                JoynTlsProxyBridge(candidate.host, candidate.port, username, password).use { bridge ->
                    val proxy = Proxy(Proxy.Type.HTTP, bridge.localAddress)
                    probeWithProxy(country, candidate, apiKey, proxy)
                }
            } catch (error: Throwable) {
                NordProbeResult.Failed("BRIDGE", summarize(error))
            }
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
            } catch (error: Throwable) {
                NordProbeResult.Failed("SOCKS", summarize(error))
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

        val actualCountry = try {
            client.newCall(
                Request.Builder()
                    .url("https://www.cloudflare.com/cdn-cgi/trace")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return NordProbeResult.Failed("EXIT", "HTTP ${response.code}")
                }
                response.body.string().lineSequence()
                    .firstOrNull { it.startsWith("loc=") }
                    ?.substringAfter("loc=")?.trim()?.uppercase()
            }
        } catch (error: Throwable) {
            return NordProbeResult.Failed(
                if (candidate.transport == JoynProxyTransport.SOCKS5) "SOCKS" else "BRIDGE",
                summarize(error),
            )
        } ?: return NordProbeResult.Failed("EXIT", "Cloudflare lieferte kein loc=")

        if (actualCountry != country.name) {
            return NordProbeResult.Failed("LAND", "Exit=$actualCountry, erwartet=${country.name}")
        }

        val startedNs = System.nanoTime()
        try {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get().build(),
            ).execute().use { response ->
                if (response.code !in 200..399) {
                    return NordProbeResult.Failed("JOYN-WEB", "HTTP ${response.code}")
                }
            }
        } catch (error: Throwable) {
            return NordProbeResult.Failed("JOYN-WEB", summarize(error))
        }

        val tokenResult = createAnonymousToken(client, country)
        val token = tokenResult.value
            ?: return NordProbeResult.Failed("JOYN-AUTH", tokenResult.error)

        val channelResult = loadFreeLiveChannelId(client, country, apiKey, token)
        val channelId = channelResult.value
            ?: return NordProbeResult.Failed("GRAPHQL", channelResult.error)

        val entitlementResult = hasLiveEntitlement(client, channelId, token)
        if (!entitlementResult.ok) {
            return NordProbeResult.Failed("ENTITLEMENT", entitlementResult.error)
        }

        val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        return NordProbeResult.Success(latency)
    }

    private fun createAnonymousToken(client: OkHttpClient, country: JoynCountry): StepValue<ProbeToken> {
        return try {
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
                if (!response.isSuccessful) {
                    return StepValue(null, "HTTP ${response.code} · ${body.take(100)}")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return StepValue(null, "Antwort ist kein JSON")
                val accessToken = json.optString("access_token").takeIf(String::isNotBlank)
                    ?: return StepValue(null, "access_token fehlt")
                StepValue(
                    ProbeToken(accessToken, json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer"),
                    "",
                )
            }
        } catch (error: Throwable) {
            StepValue(null, summarize(error))
        }
    }

    private fun loadFreeLiveChannelId(
        client: OkHttpClient,
        country: JoynCountry,
        apiKey: String,
        token: ProbeToken,
    ): StepValue<String> {
        return try {
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
                if (!response.isSuccessful) {
                    return StepValue(null, "HTTP ${response.code} · ${body.take(100)}")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return StepValue(null, "Antwort ist kein JSON")
                val errors = json.optJSONArray("errors")
                if ((errors?.length() ?: 0) > 0) {
                    return StepValue(null, "GraphQL errors · ${errors.toString().take(100)}")
                }
                val streams = json.optJSONObject("data")?.optJSONArray("liveStreams")
                    ?: return StepValue(null, "liveStreams fehlt")
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
                    if (!paid) {
                        val id = stream.optString("id").takeIf(String::isNotBlank)
                        if (id != null) return StepValue(id, "")
                    }
                }
                StepValue(null, "kein freier LINEAR-Sender in ${streams.length()} Streams")
            }
        } catch (error: Throwable) {
            StepValue(null, summarize(error))
        }
    }

    private fun hasLiveEntitlement(
        client: OkHttpClient,
        channelId: String,
        token: ProbeToken,
    ): StepFlag {
        return try {
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
                if (!response.isSuccessful) {
                    return StepFlag(false, "HTTP ${response.code} · ${body.take(140)}")
                }
                val tokenPresent = runCatching { JSONObject(body).optString("entitlement_token").isNotBlank() }
                    .getOrDefault(false)
                if (tokenPresent) StepFlag(true, "")
                else StepFlag(false, "HTTP ${response.code}, entitlement_token fehlt · ${body.take(120)}")
            }
        } catch (error: Throwable) {
            StepFlag(false, summarize(error))
        }
    }

    private fun summarize(error: Throwable): String {
        val message = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(140)
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    private data class NordServer(val hostname: String, val load: Int)
    private data class NordCandidate(
        val host: String,
        val port: Int,
        val transport: JoynProxyTransport,
        val tlsProxy: Boolean,
        val load: Int,
        val advertised: Boolean,
    )
    private data class NordDiscovery(val candidates: List<NordCandidate>, val summary: String)
    private data class ProxyPreflight(val ok: Boolean, val stage: String, val detail: String)
    private data class ProbeToken(val accessToken: String, val tokenType: String)
    private data class StepValue<T>(val value: T?, val error: String)
    private data class StepFlag(val ok: Boolean, val error: String)

    private sealed interface NordProbeResult {
        data class Success(val latencyMs: Long) : NordProbeResult
        data class Failed(val stage: String, val detail: String) : NordProbeResult
    }

    private companion object {
        private val SOCKS_AUTH_LOCK = Any()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SERVER_FETCH_LIMIT = 250
        private const val RECOMMENDATION_FETCH_LIMIT = 100
        private const val MAX_HTTPS_SERVERS = 50
        private const val MAX_SOCKS_SERVERS = 15
        private const val MAX_FAILURE_EXAMPLES = 8
        private const val PREFLIGHT_CONNECT_TIMEOUT_MS = 3_500
        private const val PREFLIGHT_READ_TIMEOUT_MS = 5_000
        private const val PROBE_CONNECT_SECONDS = 4L
        private const val PROBE_READ_SECONDS = 8L
        private const val PROBE_CALL_SECONDS = 14L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query ProxyLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
