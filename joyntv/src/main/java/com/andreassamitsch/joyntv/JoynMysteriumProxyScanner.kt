package com.andreassamitsch.joyntv

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class JoynMysteriumProxyScanner(
    private val apiClient: JoynMysteriumApiClient = JoynMysteriumApiClient(),
) {
    suspend fun findBest(
        country: JoynCountry,
        apiKey: String?,
        apiBaseUrl: String,
        maxAttempts: Int,
        allTraffic: Boolean,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynProxyDiscoveryResult = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            return@withContext JoynProxyDiscoveryResult(
                config = null,
                candidates = 0,
                attempted = 0,
                message = "Der Joyn API-Schlüssel für ${country.name} ist noch nicht im Cache. Joyn für dieses Land einmal direkt öffnen und danach erneut testen.",
            )
        }

        val attemptsLimit = maxAttempts.coerceIn(
            JoynMysteriumSettings.MIN_ATTEMPTS,
            JoynMysteriumSettings.MAX_ATTEMPTS,
        )
        JoynMysteriumScanControl.reset()

        onProgress(JoynProxyDiscoveryProgress("Prüfe lokale Mysterium-VPN-API …", 0, attemptsLimit))
        val apiStatus = apiClient.status(apiBaseUrl)
        if (!apiStatus.reachable) {
            return@withContext JoynProxyDiscoveryResult(
                config = null,
                candidates = attemptsLimit,
                attempted = 0,
                message = apiStatus.message +
                    "\n\nDie Mysterium-VPN-Komponente muss auf diesem Gerät laufen. Standard: ${JoynMysteriumSettings.DEFAULT_API_BASE_URL}",
            )
        }
        if (apiStatus.authenticated == false) {
            return@withContext JoynProxyDiscoveryResult(
                config = null,
                candidates = attemptsLimit,
                attempted = 0,
                message = apiStatus.message +
                    "\nBitte zuerst in Mysterium VPN anmelden/aktivieren und den Test danach erneut starten.",
            )
        }

        val log = mutableListOf<String>()
        log += "API: ${apiStatus.message}"
        log += apiClient.residentialLocationSummary(apiBaseUrl)

        val seenExits = linkedSetOf<String>()
        val seenLeases = linkedSetOf<String>()
        var attempted = 0
        var leasesReceived = 0
        var duplicates = 0
        var wrongCountry = 0
        var proxyFailures = 0
        var joynVpnDetected = 0
        var joynOtherFailure = 0

        while (attempted < attemptsLimit && !JoynMysteriumScanControl.isStopRequested()) {
            attempted++
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Mysterium Residential ${country.name}: fordere IP $attempted/$attemptsLimit an …\n" +
                        shortStats(leasesReceived, seenExits.size, joynVpnDetected, proxyFailures),
                    attempted = attempted - 1,
                    total = attemptsLimit,
                ),
            )

            val leaseResult = apiClient.requestResidentialProxy(
                baseUrl = apiBaseUrl,
                country = country,
                resetConnection = true,
            )
            val lease = leaseResult.getOrElse { error ->
                val reason = error.message ?: error.javaClass.simpleName
                log += "#$attempted LEASE_FEHLER · $reason"
                if (reason.contains("limit", ignoreCase = true)) {
                    return@withContext finish(
                        config = null,
                        country = country,
                        attemptsLimit = attemptsLimit,
                        attempted = attempted,
                        stopped = false,
                        leasesReceived = leasesReceived,
                        uniqueExits = seenExits.size,
                        duplicates = duplicates,
                        wrongCountry = wrongCountry,
                        proxyFailures = proxyFailures,
                        joynVpnDetected = joynVpnDetected,
                        joynOtherFailure = joynOtherFailure,
                        log = log,
                        extra = "Mysterium meldet ein IP-/Proxy-Limit; Scan wurde beendet.",
                    )
                }
                delay(RETRY_DELAY_MS)
                continue
            }
            leasesReceived++

            val leaseKey = "${lease.host}:${lease.port}:${lease.username}:${lease.expiresAt}"
            if (!seenLeases.add(leaseKey)) {
                duplicates++
                log += "#$attempted LEASE_DUPLIKAT · ${lease.host}:${lease.port}"
                delay(RETRY_DELAY_MS)
                continue
            }

            val client = proxyClient(lease)
            val trace = resolveExit(client)
            if (trace == null) {
                proxyFailures++
                log += "#$attempted PROXY_FEHLER · ${lease.host}:${lease.port} · Exit nicht ermittelbar"
                delay(RETRY_DELAY_MS)
                continue
            }

            if (!trace.country.equals(country.name, ignoreCase = true)) {
                wrongCountry++
                log += "#$attempted FALSCHES_LAND · ${trace.ip} · ${trace.country} statt ${country.name}"
                delay(RETRY_DELAY_MS)
                continue
            }

            if (!seenExits.add(trace.ip)) {
                duplicates++
                log += "#$attempted EXIT_DUPLIKAT · ${trace.ip}"
                delay(RETRY_DELAY_MS)
                continue
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Mysterium Residential ${country.name}: Exit ${trace.ip} (${seenExits.size}. eindeutige IP) → Joyn Live …",
                    attempted = attempted,
                    total = attemptsLimit,
                ),
            )

            val startedNs = System.nanoTime()
            val probe = probeJoyn(client, country, apiKey)
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            when (probe.status) {
                JoynMysteriumProbeStatus.OK -> {
                    val config = JoynProxyConfig(
                        enabled = true,
                        automatic = true,
                        transport = JoynProxyTransport.HTTP,
                        host = lease.host,
                        port = lease.port,
                        username = lease.username,
                        password = lease.password,
                        allTraffic = allTraffic,
                        source = "Mysterium · Residential · ${country.name}",
                        latencyMs = latencyMs,
                        lastVerifiedAtEpochMs = System.currentTimeMillis(),
                    )
                    log += "#$attempted OK · ${trace.ip} · Joyn Live freigegeben · ${latencyMs} ms"
                    return@withContext finish(
                        config = config,
                        country = country,
                        attemptsLimit = attemptsLimit,
                        attempted = attempted,
                        stopped = false,
                        leasesReceived = leasesReceived,
                        uniqueExits = seenExits.size,
                        duplicates = duplicates,
                        wrongCountry = wrongCountry,
                        proxyFailures = proxyFailures,
                        joynVpnDetected = joynVpnDetected,
                        joynOtherFailure = joynOtherFailure,
                        log = log,
                        extra = "Treffer: Residential-Exit ${trace.ip} besteht Joyns echte Live-Freigabe." +
                            lease.expiresAt.takeIf(String::isNotBlank)?.let { " Proxy-Lease gültig bis $it." }.orEmpty(),
                    )
                }
                JoynMysteriumProbeStatus.VPN_DETECTED -> {
                    joynVpnDetected++
                    log += "#$attempted VPN_ERKANNT · ${trace.ip} · ${probe.detail}"
                }
                JoynMysteriumProbeStatus.FAILED -> {
                    joynOtherFailure++
                    log += "#$attempted JOYN_FEHLER · ${trace.ip} · ${probe.detail}"
                }
            }
            delay(RETRY_DELAY_MS)
        }

        finish(
            config = null,
            country = country,
            attemptsLimit = attemptsLimit,
            attempted = attempted,
            stopped = JoynMysteriumScanControl.isStopRequested(),
            leasesReceived = leasesReceived,
            uniqueExits = seenExits.size,
            duplicates = duplicates,
            wrongCountry = wrongCountry,
            proxyFailures = proxyFailures,
            joynVpnDetected = joynVpnDetected,
            joynOtherFailure = joynOtherFailure,
            log = log,
            extra = if (JoynMysteriumScanControl.isStopRequested()) {
                "Scan vom Benutzer gestoppt."
            } else {
                "Kein getesteter Residential-Exit bestand Joyns Live-Freigabe."
            },
        )
    }

    private fun proxyClient(lease: JoynMysteriumProxyLease): OkHttpClient {
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(lease.host, lease.port),
        )
        val credential = Credentials.basic(lease.username, lease.password)
        return OkHttpClient.Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
            .connectTimeout(PROXY_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROXY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROXY_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun resolveExit(client: OkHttpClient): ExitTrace? = runCatching {
        client.newCall(
            Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val values = response.body.string()
                .lineSequence()
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }
                .toMap()
            val ip = values["ip"].orEmpty().trim()
            val country = values["loc"].orEmpty().trim().uppercase()
            if (ip.isBlank() || country.isBlank()) null else ExitTrace(ip, country)
        }
    }.getOrNull()

    private fun probeJoyn(
        client: OkHttpClient,
        country: JoynCountry,
        apiKey: String,
    ): JoynMysteriumProbeResult {
        val webOk = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        if (!webOk) return JoynMysteriumProbeResult.failed("Joyn-Web nicht erreichbar")

        val token = createAnonymousToken(client, country)
            ?: return JoynMysteriumProbeResult.failed("anonymer Joyn-Login fehlgeschlagen")
        val channelId = loadFreeLiveChannel(client, country, apiKey, token)
            ?: return JoynMysteriumProbeResult.failed("GraphQL/Free-Live-Kanal fehlgeschlagen")
        return checkEntitlement(client, channelId, token)
    }

    private fun createAnonymousToken(
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

    private fun loadFreeLiveChannel(
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
            val streams = JSONObject(body).optJSONObject("data")?.optJSONArray("liveStreams")
                ?: return@runCatching null
            for (i in 0 until streams.length()) {
                val stream = streams.optJSONObject(i) ?: continue
                val markings = stream.optJSONArray("markings")
                var paid = false
                if (markings != null) {
                    for (j in 0 until markings.length()) {
                        if (markings.optString(j) in setOf("PLUS", "PREMIUM")) {
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

    private fun checkEntitlement(
        client: OkHttpClient,
        channelId: String,
        token: ProbeToken,
    ): JoynMysteriumProbeResult = runCatching {
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
            val vpnDetected = body.contains("ENT_USER_VPN_DETECTED", ignoreCase = true) ||
                body.contains("VPN_DETECTED", ignoreCase = true)
            if (vpnDetected) {
                return@runCatching JoynMysteriumProbeResult(
                    JoynMysteriumProbeStatus.VPN_DETECTED,
                    "ENT_USER_VPN_DETECTED",
                )
            }
            if (!response.isSuccessful) {
                return@runCatching JoynMysteriumProbeResult.failed(
                    "Entitlement HTTP ${response.code}: ${compact(body)}",
                )
            }
            val tokenValue = runCatching { JSONObject(body).optString("entitlement_token") }.getOrDefault("")
            if (tokenValue.isNotBlank()) {
                JoynMysteriumProbeResult(JoynMysteriumProbeStatus.OK, "Live freigegeben")
            } else {
                JoynMysteriumProbeResult.failed("Entitlement ohne Token: ${compact(body)}")
            }
        }
    }.getOrElse { error ->
        JoynMysteriumProbeResult.failed("Entitlement ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }

    private fun finish(
        config: JoynProxyConfig?,
        country: JoynCountry,
        attemptsLimit: Int,
        attempted: Int,
        stopped: Boolean,
        leasesReceived: Int,
        uniqueExits: Int,
        duplicates: Int,
        wrongCountry: Int,
        proxyFailures: Int,
        joynVpnDetected: Int,
        joynOtherFailure: Int,
        log: List<String>,
        extra: String,
    ): JoynProxyDiscoveryResult {
        val message = buildString {
            append(if (config != null) "Mysterium Residential erfolgreich" else if (stopped) "Mysterium-Test gestoppt" else "Mysterium-Test abgeschlossen")
            append(" · ${country.name}\n")
            append("Versuche=$attempted/$attemptsLimit · Leases=$leasesReceived · eindeutige Exits=$uniqueExits")
            append(" · Joyn VPN erkannt=$joynVpnDetected")
            append(" · sonstige Joyn-Fehler=$joynOtherFailure")
            append(" · Proxyfehler=$proxyFailures · falsches Land=$wrongCountry · Duplikate=$duplicates\n")
            append(extra)
            if (log.isNotEmpty()) {
                append("\n\nTestlog:\n")
                append(log.takeLast(MAX_LOG_LINES).joinToString("\n"))
                if (log.size > MAX_LOG_LINES) append("\n… ${log.size - MAX_LOG_LINES} ältere Einträge ausgeblendet")
            }
        }
        return JoynProxyDiscoveryResult(
            config = config,
            candidates = attemptsLimit,
            attempted = attempted,
            message = message,
        )
    }

    private fun shortStats(leases: Int, exits: Int, vpn: Int, proxyFailures: Int): String =
        "Leases=$leases · Exits=$exits · VPN erkannt=$vpn · Proxyfehler=$proxyFailures"

    private fun compact(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(220)

    private data class ExitTrace(val ip: String, val country: String)
    private data class ProbeToken(val accessToken: String, val tokenType: String)

    private enum class JoynMysteriumProbeStatus { OK, VPN_DETECTED, FAILED }

    private data class JoynMysteriumProbeResult(
        val status: JoynMysteriumProbeStatus,
        val detail: String,
    ) {
        companion object {
            fun failed(detail: String) = JoynMysteriumProbeResult(JoynMysteriumProbeStatus.FAILED, detail)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val RETRY_DELAY_MS = 350L
        private const val PROXY_CONNECT_TIMEOUT_SECONDS = 6L
        private const val PROXY_READ_TIMEOUT_SECONDS = 10L
        private const val PROXY_CALL_TIMEOUT_SECONDS = 18L
        private const val MAX_LOG_LINES = 80
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query MysteriumLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
