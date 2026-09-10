package com.andreassamitsch.joyntv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers NordVPN HTTPS proxy exits for the selected Joyn country and proves them end-to-end.
 *
 * The country scan deliberately uses only Nord servers advertised as proxy_ssl/proxy_ssl_cybersec.
 * Ordinary VPN servers are never guessed as SOCKS5 or HTTPS proxies. Every port-89 server is first
 * checked through Cloudflare trace; servers sharing the same public exit IP are deduplicated before
 * the expensive Joyn entitlement check. Exit discovery and Joyn checks run in small parallel batches.
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

        onProgress(JoynProxyDiscoveryProgress("Lade alle NordVPN-proxy_ssl-Server für ${country.name} …"))
        val discovery = loadCandidates(country)
        val candidates = discovery.candidates
        if (candidates.isEmpty()) {
            return JoynProxyDiscoveryResult(
                null,
                0,
                0,
                "NordVPN lieferte keine als proxy_ssl markierten Server für ${country.name}. ${discovery.summary}",
            )
        }

        onProgress(
            JoynProxyDiscoveryProgress(
                "NordVPN ${country.name}: ${candidates.size} echte HTTPS/89-Proxyserver. Ermittle Exit-IPs …",
                total = candidates.size,
            ),
        )

        val failures = linkedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        fun recordFailure(candidate: NordCandidate, stage: String, detail: String) {
            failures[stage] = (failures[stage] ?: 0) + 1
            if (examples.size < MAX_FAILURE_EXAMPLES) {
                examples += "${candidate.host}:89 [$stage] ${detail.take(150)}"
            }
        }

        val uniqueExits = linkedMapOf<String, NordExit>()
        var exitAttempted = 0
        var duplicateExits = 0
        var wrongCountry = 0

        for (batch in candidates.chunked(EXIT_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) { discoverExit(country, candidate, username, password) }
                }.awaitAll()
            }

            results.forEach { result ->
                exitAttempted++
                if (result.exitIp == null || result.exitCountry == null) {
                    recordFailure(result.candidate, result.stage ?: "EXIT", result.detail)
                    return@forEach
                }
                if (result.exitCountry != country.name) {
                    wrongCountry++
                    recordFailure(
                        result.candidate,
                        "LAND",
                        "Exit=${result.exitCountry}, erwartet=${country.name}, IP=${result.exitIp}",
                    )
                    return@forEach
                }

                if (uniqueExits.containsKey(result.exitIp)) {
                    duplicateExits++
                } else {
                    uniqueExits[result.exitIp] = NordExit(
                        candidate = result.candidate,
                        exitIp = result.exitIp,
                    )
                }
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    "Exit-IP-Prüfung $exitAttempted/${candidates.size} · eindeutig=${uniqueExits.size} · Duplikate=$duplicateExits · falsches Land=$wrongCountry",
                    exitAttempted,
                    candidates.size,
                ),
            )
        }

        if (uniqueExits.isEmpty()) {
            val failureSummary = summarizeFailures(failures)
            val exampleSummary = exampleSummary(examples)
            return JoynProxyDiscoveryResult(
                null,
                candidates.size,
                exitAttempted,
                "Kein NordVPN-proxy_ssl-Server für ${country.name} lieferte eine brauchbare Exit-IP. " +
                    "Server=${candidates.size} · $failureSummary.$exampleSummary\n${discovery.summary}",
            )
        }

        val exits = uniqueExits.values.toList()
        onProgress(
            JoynProxyDiscoveryProgress(
                "${candidates.size} proxy_ssl-Server → ${exits.size} eindeutige ${country.name}-Exit-IPs. Starte Joyn-Live-Prüfung …",
                total = exits.size,
            ),
        )

        var joynAttempted = 0
        var vpnDetected = 0
        for (batch in exits.chunked(JOYN_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { exit ->
                    async(Dispatchers.IO) {
                        JoynExitProbe(exit, probeJoyn(country, exit, apiKey, username, password))
                    }
                }.awaitAll()
            }

            val successes = mutableListOf<Pair<NordExit, NordProbeResult.Success>>()
            results.forEach { result ->
                joynAttempted++
                when (val probe = result.probe) {
                    is NordProbeResult.Success -> successes += result.exit to probe
                    is NordProbeResult.Failed -> {
                        if (probe.stage == "VPN") vpnDetected++
                        recordFailure(result.exit.candidate, probe.stage, "IP=${result.exit.exitIp} · ${probe.detail}")
                    }
                }
            }

            if (successes.isNotEmpty()) {
                val (winner, probe) = successes.minBy { it.second.latencyMs }
                val config = JoynProxyConfig(
                    enabled = true,
                    automatic = true,
                    transport = JoynProxyTransport.HTTP,
                    host = winner.candidate.host,
                    port = HTTPS_PROXY_PORT,
                    username = username,
                    password = password,
                    allTraffic = allTraffic,
                    source = "${JoynProxyConfig.NORD_TLS_SOURCE_PREFIX} 89",
                    latencyMs = probe.latencyMs,
                    lastVerifiedAtEpochMs = System.currentTimeMillis(),
                )
                val message =
                    "Joyn-tauglicher NordVPN-Exit gefunden: ${winner.candidate.host}:89 · IP=${winner.exitIp} · ${probe.latencyMs} ms · " +
                        "$joynAttempted/${exits.size} eindeutige Exit-IPs geprüft"
                onProgress(JoynProxyDiscoveryProgress(message, joynAttempted, exits.size))
                return JoynProxyDiscoveryResult(config, candidates.size, exitAttempted, message)
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    "Joyn-Live $joynAttempted/${exits.size} Exit-IPs · VPN erkannt=$vpnDetected · suche weiter …",
                    joynAttempted,
                    exits.size,
                ),
            )
        }

        val failureSummary = summarizeFailures(failures)
        val examplesText = exampleSummary(examples)
        val message =
            "Kein NordVPN-Exit für ${country.name} bestand Joyn Live. " +
                "${candidates.size} echte proxy_ssl-Server → ${exits.size} eindeutige Exit-IPs; " +
                "Joyn geprüft=$joynAttempted · VPN erkannt=$vpnDetected · Duplikate=$duplicateExits · falsches Land=$wrongCountry. " +
                "$failureSummary.$examplesText\n${discovery.summary}"
        return JoynProxyDiscoveryResult(null, candidates.size, exitAttempted, message)
    }

    private fun loadCandidates(country: JoynCountry): NordDiscovery {
        val proxySsl = loadAllTechnologyServers(country, "proxy_ssl")
        val proxySslCyberSec = loadAllTechnologyServers(country, "proxy_ssl_cybersec")
        val servers = (proxySsl + proxySslCyberSec)
            .distinctBy { it.hostname }
            .sortedBy { it.load }

        val candidates = servers.map { server ->
            NordCandidate(host = server.hostname, load = server.load)
        }
        return NordDiscovery(
            candidates = candidates,
            summary = "Serverauswahl: HTTPS/89 ${candidates.size} echte proxy_ssl/proxy_ssl_cybersec-Server; " +
                "keine normalen VPN-Server und keine geratenen SOCKS5-Fallbacks.",
        )
    }

    private fun loadAllTechnologyServers(country: JoynCountry, technology: String): List<NordServer> {
        val countryId = countryId(country)
        val expectedName = expectedCountryName(country)
        val collected = linkedMapOf<String, NordServer>()

        for (page in 0 until SERVER_MAX_PAGES) {
            val offset = page * SERVER_PAGE_SIZE
            val url = "https://api.nordvpn.com/v1/servers".toHttpUrl().newBuilder()
                .addQueryParameter("limit", SERVER_PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("filters[country_id]", countryId.toString())
                .addQueryParameter("filters[servers_technologies][identifier]", technology)
                .build()
            val body = executeDirect(url.toString()) ?: break
            val array = runCatching { JSONArray(body) }.getOrNull() ?: break
            val parsed = parseV1Servers(array, country, expectedName)
            val before = collected.size
            parsed.forEach { collected.putIfAbsent(it.hostname, it) }
            val added = collected.size - before
            if (array.length() < SERVER_PAGE_SIZE || added == 0) break
        }

        // Recommendations are a useful fallback when the paged directory endpoint changes its
        // pagination semantics. Merge them instead of replacing the full directory result.
        val recommendationUrl = "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter("limit", RECOMMENDATION_FETCH_LIMIT.toString())
            .addQueryParameter("filters[country_id]", countryId.toString())
            .addQueryParameter("filters[servers_technologies][identifier]", technology)
            .build()
        executeDirect(recommendationUrl.toString())
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { parseV1Servers(it, country, expectedName) }
            ?.forEach { collected.putIfAbsent(it.hostname, it) }

        return collected.values.toList()
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

    private fun discoverExit(
        country: JoynCountry,
        candidate: NordCandidate,
        username: String,
        password: String,
    ): ExitDiscoveryResult {
        val preflight = preflightTlsProxy(candidate, username, password)
        if (!preflight.ok) {
            return ExitDiscoveryResult(candidate, null, null, preflight.stage, preflight.detail)
        }

        return try {
            JoynTlsProxyBridge(candidate.host, HTTPS_PROXY_PORT, username, password).use { bridge ->
                val client = directClient.newBuilder()
                    .proxy(Proxy(Proxy.Type.HTTP, bridge.localAddress))
                    .connectTimeout(PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(PROBE_READ_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)
                    .build()

                client.newCall(
                    Request.Builder()
                        .url(CLOUDFLARE_TRACE_URL)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        return ExitDiscoveryResult(candidate, null, null, "EXIT", "HTTP ${response.code}")
                    }
                    val fields = response.body.string().lineSequence()
                        .mapNotNull { line ->
                            val split = line.indexOf('=')
                            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                        }
                        .toMap()
                    val ip = fields["ip"]?.trim()?.takeIf(String::isNotBlank)
                    val loc = fields["loc"]?.trim()?.uppercase()?.takeIf(String::isNotBlank)
                    if (ip == null || loc == null) {
                        ExitDiscoveryResult(candidate, null, null, "EXIT", "Cloudflare trace ohne ip=/loc=")
                    } else {
                        ExitDiscoveryResult(candidate, ip, loc, null, "")
                    }
                }
            }
        } catch (error: Throwable) {
            ExitDiscoveryResult(candidate, null, null, "EXIT", summarize(error))
        }
    }

    private fun preflightTlsProxy(
        candidate: NordCandidate,
        username: String,
        password: String,
    ): ProxyPreflight {
        var tls: SSLSocket? = null
        return try {
            val tlsConnection = JoynNordProxyTls.connect(
                host = candidate.host,
                port = HTTPS_PROXY_PORT,
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
            var proxyAuthenticate = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Proxy-Authenticate:", ignoreCase = true)) {
                    proxyAuthenticate = line.substringAfter(':').trim()
                }
            }
            when {
                statusLine.contains(" 200 ") -> ProxyPreflight(true, "CONNECT", "HTTP 200")
                statusLine.contains(" 407 ") -> ProxyPreflight(
                    false,
                    "AUTH",
                    buildString {
                        append("HTTP 407")
                        if (proxyAuthenticate.isNotBlank()) append(" · Proxy-Authenticate=$proxyAuthenticate")
                    },
                )
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

    private fun probeJoyn(
        country: JoynCountry,
        exit: NordExit,
        apiKey: String,
        username: String,
        password: String,
    ): NordProbeResult {
        return try {
            JoynTlsProxyBridge(exit.candidate.host, HTTPS_PROXY_PORT, username, password).use { bridge ->
                val client = directClient.newBuilder()
                    .proxy(Proxy(Proxy.Type.HTTP, bridge.localAddress))
                    .connectTimeout(PROBE_CONNECT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(PROBE_READ_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)
                    .build()
                probeJoynWithClient(country, apiKey, client)
            }
        } catch (error: Throwable) {
            NordProbeResult.Failed("BRIDGE", summarize(error))
        }
    }

    private fun probeJoynWithClient(
        country: JoynCountry,
        apiKey: String,
        client: OkHttpClient,
    ): NordProbeResult {
        val startedNs = System.nanoTime()
        try {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build(),
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
            val stage = if (entitlementResult.error.contains("ENT_USER_VPN_DETECTED", ignoreCase = true)) {
                "VPN"
            } else {
                "ENTITLEMENT"
            }
            return NordProbeResult.Failed(stage, entitlementResult.error)
        }

        return NordProbeResult.Success(
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs),
        )
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
                    return StepValue(null, "HTTP ${response.code} · ${body.take(120)}")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return StepValue(null, "Antwort ist kein JSON")
                val accessToken = json.optString("access_token").takeIf(String::isNotBlank)
                    ?: return StepValue(null, "access_token fehlt")
                StepValue(
                    ProbeToken(
                        accessToken,
                        json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer",
                    ),
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
                if (!response.isSuccessful) {
                    return StepValue(null, "HTTP ${response.code} · ${body.take(120)}")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return StepValue(null, "Antwort ist kein JSON")
                val errors = json.optJSONArray("errors")
                if ((errors?.length() ?: 0) > 0) {
                    return StepValue(null, "GraphQL errors · ${errors.toString().take(120)}")
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
                    return StepFlag(false, "HTTP ${response.code} · ${body.take(180)}")
                }
                val tokenPresent = runCatching {
                    JSONObject(body).optString("entitlement_token").isNotBlank()
                }.getOrDefault(false)
                if (tokenPresent) StepFlag(true, "")
                else StepFlag(false, "HTTP ${response.code}, entitlement_token fehlt · ${body.take(150)}")
            }
        } catch (error: Throwable) {
            StepFlag(false, summarize(error))
        }
    }

    private fun summarizeFailures(failures: Map<String, Int>): String =
        if (failures.isEmpty()) "keine weiteren Fehler"
        else failures.entries.joinToString(" · ") { (stage, count) -> "$stage=$count" }

    private fun exampleSummary(examples: List<String>): String =
        if (examples.isEmpty()) "" else "\nBeispiele:\n" + examples.joinToString("\n")

    private fun summarize(error: Throwable): String {
        val message = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(160)
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

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

    private data class NordServer(val hostname: String, val load: Int)
    private data class NordCandidate(val host: String, val load: Int)
    private data class NordDiscovery(val candidates: List<NordCandidate>, val summary: String)
    private data class NordExit(val candidate: NordCandidate, val exitIp: String)
    private data class ExitDiscoveryResult(
        val candidate: NordCandidate,
        val exitIp: String?,
        val exitCountry: String?,
        val stage: String?,
        val detail: String,
    )
    private data class ProxyPreflight(val ok: Boolean, val stage: String, val detail: String)
    private data class ProbeToken(val accessToken: String, val tokenType: String)
    private data class StepValue<T>(val value: T?, val error: String)
    private data class StepFlag(val ok: Boolean, val error: String)
    private data class JoynExitProbe(val exit: NordExit, val probe: NordProbeResult)

    private sealed interface NordProbeResult {
        data class Success(val latencyMs: Long) : NordProbeResult
        data class Failed(val stage: String, val detail: String) : NordProbeResult
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val HTTPS_PROXY_PORT = 89
        private const val SERVER_PAGE_SIZE = 250
        private const val SERVER_MAX_PAGES = 8
        private const val RECOMMENDATION_FETCH_LIMIT = 500
        private const val EXIT_BATCH_SIZE = 8
        private const val JOYN_BATCH_SIZE = 4
        private const val MAX_FAILURE_EXAMPLES = 8
        private const val PREFLIGHT_CONNECT_TIMEOUT_MS = 3_500
        private const val PREFLIGHT_READ_TIMEOUT_MS = 5_000
        private const val PROBE_CONNECT_SECONDS = 4L
        private const val PROBE_READ_SECONDS = 8L
        private const val PROBE_CALL_SECONDS = 14L
        private const val CLOUDFLARE_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query ProxyLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
