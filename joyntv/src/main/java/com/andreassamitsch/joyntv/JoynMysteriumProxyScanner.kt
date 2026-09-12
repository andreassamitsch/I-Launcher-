package com.andreassamitsch.joyntv

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Finds a Mysterium Residential HTTP proxy lease that Joyn actually accepts.
 *
 * Mysterium's connect-proxy credentials represent a reusable proxy lease, not a guaranteed fixed
 * public exit IP. Authenticated PC measurements showed that separate CONNECTs with the same lease
 * can use different public exits while Joyn still grants entitlement. The scanner therefore asks
 * Mysterium for a lease once and probes that lease repeatedly. A fresh API lease is requested only
 * after repeated transport-level failures; VPN/geo decisions from Joyn merely trigger another
 * CONNECT through the same lease.
 */
internal class JoynMysteriumProxyScanner(
    context: Context,
    private val apiClient: JoynMysteriumApiClient,
) {
    private val appContext = context.applicationContext
    private val settings = JoynMysteriumSettings(appContext)

    suspend fun findBest(
        country: JoynCountry,
        apiKey: String?,
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

        // Mysterium's control API and upstream proxy socket must be reached directly. The selected
        // lease is installed as process proxy only after Joyn has accepted it.
        JoynMysteriumWireGuard.disconnect(appContext)
        JoynProxySettings.installDirectForTunnel()

        onProgress(JoynProxyDiscoveryProgress("Prüfe Mysterium-Konto …", 0, attemptsLimit))
        val apiStatus = apiClient.status()
        if (!apiStatus.reachable) {
            return@withContext JoynProxyDiscoveryResult(null, attemptsLimit, 0, apiStatus.message)
        }
        if (apiStatus.authenticated != true) {
            return@withContext JoynProxyDiscoveryResult(
                null,
                attemptsLimit,
                0,
                apiStatus.message + "\nBitte zuerst bei Mysterium anmelden.",
            )
        }

        val log = mutableListOf<String>()
        log += "API: ${apiStatus.message}"
        log += apiClient.residentialLocationSummary()
        log += "Transport: Mysterium HTTP CONNECT · lokale Auth-Bridge · TLS:443 für verifizierte EU-Superproxies · kein Android VPN"
        log += "Routing: gesamter Joyn-TV-Traffic inkl. Manifest, DRM und Stream-Segmente"
        log += "Auswahl: Joyn-Entitlement entscheidet; öffentliche Trace-IP dient nur der Diagnose"
        log += "Lease-Strategie: ein Residential-Lease wird über viele CONNECTs wiederverwendet; neuer Lease nur bei wiederholten Transportfehlern"
        if (!allTraffic) {
            log += "Hinweis: Proxy-Modus erzwingt für Leckschutz den gesamten Joyn-TV-Traffic."
        }

        val seenExits = linkedSetOf<String>()
        val seenLeases = linkedSetOf<String>()
        var attempted = 0
        var leasesReceived = 0
        var duplicates = 0
        var wrongCountry = 0
        var proxyFailures = 0
        var joynVpnDetected = 0
        var joynOtherFailure = 0
        var consecutiveRouteFailures = 0
        var initialLeaseFailures = 0
        var activeLease: JoynMysteriumProxyLease? = null

        while (attempted < attemptsLimit && !JoynMysteriumScanControl.isStopRequested()) {
            if (activeLease == null) {
                onProgress(
                    JoynProxyDiscoveryProgress(
                        message = "Mysterium Residential ${country.name}: fordere Residential-Lease an …\n" +
                            shortStats(leasesReceived, seenExits.size, joynVpnDetected, proxyFailures),
                        attempted = attempted,
                        total = attemptsLimit,
                    ),
                )

                val leaseResult = apiClient.requestResidentialProxy(country = country, resetConnection = true)
                activeLease = leaseResult.getOrElse { error ->
                    initialLeaseFailures++
                    val reason = error.message ?: error.javaClass.simpleName
                    log += "LEASE_FEHLER #$initialLeaseFailures · $reason"
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
                    if (initialLeaseFailures >= MAX_INITIAL_LEASE_FAILURES) {
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
                            extra = "Mysterium lieferte nach mehreren Versuchen keinen Residential-Lease. Die Control-API wird nicht weiter belastet.",
                        )
                    }
                    delay(LEASE_API_RETRY_BASE_MS * initialLeaseFailures)
                    continue
                }
                initialLeaseFailures = 0
                leasesReceived++
                consecutiveRouteFailures = 0

                val lease = activeLease!!
                val leaseKey = leaseKey(lease)
                if (!seenLeases.add(leaseKey)) {
                    duplicates++
                    log += "LEASE_DUPLIKAT · ${lease.host}:${lease.port} · wird trotzdem weiterverwendet"
                } else {
                    log += "LEASE_OK · ${lease.host}:${lease.port} · wird für mehrere CONNECTs wiederverwendet"
                }
            }

            val lease = activeLease!!
            attempted++

            // Trace is deliberately non-authoritative. It helps diagnose country/rotation but the
            // Joyn request below may leave through another public exit on the same lease.
            val exitAttempt = throughProxyLease(lease) { client -> resolveExit(client) }
            val trace = exitAttempt.value
            if (trace == null) {
                log += "#$attempted TRACE_FEHLER · ${exitAttempt.failure.ifBlank { "öffentliche Exit-IP nicht ermittelbar" }} · Joyn-Test läuft trotzdem"
            } else {
                if (!trace.country.equals(country.name, ignoreCase = true)) {
                    wrongCountry++
                    log += "#$attempted TRACE_LAND_ABWEICHUNG · ${trace.ip} · ${trace.country} statt ${country.name} · nur Diagnose"
                }
                if (!seenExits.add(trace.ip)) {
                    duplicates++
                    log += "#$attempted EXIT_DUPLIKAT · ${trace.ip} · derselbe Lease wird trotzdem weiter getestet"
                }
            }

            val traceLabel = trace?.let { "${it.ip}/${it.country}" } ?: "keine Trace-IP"
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Mysterium Residential ${country.name}: $traceLabel → Joyn Live-Freigabe $attempted/$attemptsLimit …\n" +
                        shortStats(leasesReceived, seenExits.size, joynVpnDetected, proxyFailures),
                    attempted = attempted,
                    total = attemptsLimit,
                ),
            )

            val startedNs = System.nanoTime()
            val probeAttempt = throughProxyLease(lease) { client -> probeJoyn(client, country, apiKey) }
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            val probe = probeAttempt.value ?: ProbeResult.failed(
                probeAttempt.failure.ifBlank { "Proxyverbindung während Joyn-Test abgebrochen" },
            )

            when (probe.status) {
                ProbeStatus.OK -> {
                    val afterAttempt = throughProxyLease(lease) { client -> resolveExit(client) }
                    val after = afterAttempt.value
                    after?.let { seenExits.add(it.ip) }
                    val rotationNote = when {
                        after == null -> "Exit nach Joyn-Test nicht erneut messbar"
                        trace == null -> "Exit nach Joyn-Test ${after.ip}/${after.country}"
                        after.ip != trace.ip -> "Exit-Rotation ${trace.ip}/${trace.country} → ${after.ip}/${after.country}"
                        !after.country.equals(trace.country, ignoreCase = true) ->
                            "Exit-Landwechsel ${trace.ip}/${trace.country} → ${after.ip}/${after.country}"
                        else -> "Trace ${after.ip}/${after.country} unverändert"
                    }

                    val config = JoynProxyConfig(
                        enabled = true,
                        automatic = true,
                        transport = JoynProxyTransport.HTTP,
                        host = lease.host,
                        port = lease.port,
                        username = lease.username,
                        password = lease.password,
                        allTraffic = true,
                        source = "Mysterium Proxy · Residential · ${country.name}",
                        latencyMs = latencyMs,
                        lastVerifiedAtEpochMs = System.currentTimeMillis(),
                    )
                    settings.saveLastSuccessful(country, config, lease.expiresAt)
                    log += "#$attempted OK · Joyn Live freigegeben · ${latencyMs} ms · $rotationNote"
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
                        extra = "Treffer: derselbe Mysterium-Lease bestand Joyns Live-Freigabe. Exit-Rotation wird toleriert.",
                        expiresAt = lease.expiresAt,
                    )
                }

                ProbeStatus.VPN_DETECTED -> {
                    joynVpnDetected++
                    consecutiveRouteFailures = 0
                    log += "#$attempted VPN_ERKANNT · $traceLabel · ${probe.detail} · nächster CONNECT mit demselben Lease"
                }

                ProbeStatus.FAILED -> {
                    joynOtherFailure++
                    val routeFailure = isLikelyRouteFailure(probe, probeAttempt)
                    if (routeFailure) {
                        proxyFailures++
                        consecutiveRouteFailures++
                        log += "#$attempted ROUTE_FEHLER · $traceLabel · ${probe.detail} · Folgefehler=$consecutiveRouteFailures/$ROUTE_FAILURES_BEFORE_REFRESH"
                    } else {
                        consecutiveRouteFailures = 0
                        log += "#$attempted JOYN_FEHLER · $traceLabel · ${probe.detail} · nächster CONNECT mit demselben Lease"
                    }
                }
            }

            if (consecutiveRouteFailures >= ROUTE_FAILURES_BEFORE_REFRESH && attempted < attemptsLimit) {
                onProgress(
                    JoynProxyDiscoveryProgress(
                        message = "Mysterium Residential ${country.name}: Lease mehrfach nicht routbar · versuche einmalig neue Credentials …",
                        attempted = attempted,
                        total = attemptsLimit,
                    ),
                )
                delay(LEASE_REFRESH_BACKOFF_MS)
                val refresh = apiClient.requestResidentialProxy(country = country, resetConnection = true)
                refresh.onSuccess { refreshed ->
                    val previousKey = leaseKey(lease)
                    val refreshedKey = leaseKey(refreshed)
                    activeLease = refreshed
                    leasesReceived++
                    consecutiveRouteFailures = 0
                    if (!seenLeases.add(refreshedKey) || refreshedKey == previousKey) {
                        duplicates++
                        log += "LEASE_REFRESH_DUPLIKAT · ${refreshed.host}:${refreshed.port} · Credentials bleiben verwendbar"
                    } else {
                        log += "LEASE_REFRESH_OK · ${refreshed.host}:${refreshed.port}"
                    }
                }.onFailure { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    log += "LEASE_REFRESH_FEHLER · $reason · vorhandener Lease bleibt aktiv; kein API-Spam"
                    consecutiveRouteFailures = 0
                }
            }

            delay(PROBE_RETRY_DELAY_MS)
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
                "Keiner der CONNECT-Versuche über den erhaltenen Mysterium-Lease bestand Joyns Live-Freigabe."
            },
        )
    }

    private fun <T> throughProxyLease(
        lease: JoynMysteriumProxyLease,
        block: (OkHttpClient) -> T,
    ): ProxyAttempt<T> {
        var bridgeFailure = ""
        return try {
            JoynMysteriumProxyBridge(
                remoteHost = lease.host,
                remotePort = lease.port,
                username = lease.username,
                password = lease.password,
                onFailure = { reason -> bridgeFailure = reason },
            ).use { bridge ->
                val client = proxyClient(bridge.localAddress)
                ProxyAttempt(block(client), bridgeFailure)
            }
        } catch (error: Throwable) {
            ProxyAttempt(
                value = null,
                failure = bridgeFailure.ifBlank {
                    buildString {
                        append(error.javaClass.simpleName)
                        error.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                    }
                },
            )
        }
    }

    private fun proxyClient(localProxyAddress: InetSocketAddress): OkHttpClient {
        val proxy = Proxy(Proxy.Type.HTTP, localProxyAddress)
        return OkHttpClient.Builder()
            .proxy(proxy)
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
            val values = response.body.string().lineSequence().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
            val ip = values["ip"].orEmpty().trim()
            val country = values["loc"].orEmpty().trim().uppercase()
            if (ip.isBlank() || country.isBlank() || ip.contains(':')) null else ExitTrace(ip, country)
        }
    }.getOrNull()

    private fun probeJoyn(client: OkHttpClient, country: JoynCountry, apiKey: String): ProbeResult {
        val webOk = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        if (!webOk) return ProbeResult.failed("Joyn-Web nicht erreichbar")

        val token = createAnonymousToken(client, country)
            ?: return ProbeResult.failed("anonymer Joyn-Login fehlgeschlagen")
        val channelId = loadFreeLiveChannel(client, country, apiKey, token)
            ?: return ProbeResult.failed("GraphQL/Free-Live-Kanal fehlgeschlagen")
        return checkEntitlement(client, channelId, token)
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
            val streams = JSONObject(body).optJSONObject("data")?.optJSONArray("liveStreams") ?: return@runCatching null
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
                if (!paid) stream.optString("id").takeIf(String::isNotBlank)?.let { return@runCatching it }
            }
            null
        }
    }.getOrNull()

    private fun checkEntitlement(client: OkHttpClient, channelId: String, token: ProbeToken): ProbeResult = runCatching {
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
            if (body.contains("ENT_USER_VPN_DETECTED", ignoreCase = true) || body.contains("VPN_DETECTED", ignoreCase = true)) {
                return@runCatching ProbeResult(ProbeStatus.VPN_DETECTED, "ENT_USER_VPN_DETECTED")
            }
            if (!response.isSuccessful) {
                return@runCatching ProbeResult.failed("Entitlement HTTP ${response.code}: ${compact(body)}")
            }
            val entitlement = runCatching { JSONObject(body).optString("entitlement_token") }.getOrDefault("")
            if (entitlement.isNotBlank()) ProbeResult(ProbeStatus.OK, "Live freigegeben")
            else ProbeResult.failed("Entitlement ohne Token: ${compact(body)}")
        }
    }.getOrElse { error ->
        ProbeResult.failed("Entitlement ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }

    private fun isLikelyRouteFailure(probe: ProbeResult, attempt: ProxyAttempt<ProbeResult>): Boolean {
        if (attempt.value == null && attempt.failure.isNotBlank()) return true
        val detail = probe.detail.lowercase()
        return detail.startsWith("joyn-web nicht erreichbar") ||
            detail.startsWith("anonymer joyn-login fehlgeschlagen") ||
            detail.startsWith("graphql/free-live-kanal fehlgeschlagen")
    }

    private fun leaseKey(lease: JoynMysteriumProxyLease): String =
        "${lease.host}:${lease.port}:${lease.username}:${lease.expiresAt}"

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
        expiresAt: String = "",
    ): JoynProxyDiscoveryResult {
        val message = buildString {
            append(if (config != null) "Mysterium Proxy erfolgreich" else if (stopped) "Mysterium-Test gestoppt" else "Mysterium-Test abgeschlossen")
            append(" · ${country.name}\n")
            append("Joyn-Probes=$attempted/$attemptsLimit · API-Leases=$leasesReceived · beobachtete Exits=$uniqueExits")
            append(" · Joyn VPN erkannt=$joynVpnDetected")
            append(" · sonstige Joyn-Fehler=$joynOtherFailure")
            append(" · Routefehler=$proxyFailures · Trace-Landabweichungen=$wrongCountry · Duplikate=$duplicates\n")
            append(extra)
            if (expiresAt.isNotBlank()) append(" · Lease bis $expiresAt")
            if (log.isNotEmpty()) {
                append("\n\nTestlog:\n")
                append(log.takeLast(MAX_LOG_LINES).joinToString("\n"))
                if (log.size > MAX_LOG_LINES) append("\n… ${log.size - MAX_LOG_LINES} ältere Einträge ausgeblendet")
            }
        }
        return JoynProxyDiscoveryResult(config, attemptsLimit, attempted, message, expiresAt)
    }

    private fun shortStats(leases: Int, exits: Int, vpn: Int, proxyFailures: Int): String =
        "API-Leases=$leases · Exits=$exits · VPN erkannt=$vpn · Routefehler=$proxyFailures"

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(220)

    private data class ProxyAttempt<T>(val value: T?, val failure: String)
    private data class ExitTrace(val ip: String, val country: String)
    private data class ProbeToken(val accessToken: String, val tokenType: String)
    private enum class ProbeStatus { OK, VPN_DETECTED, FAILED }
    private data class ProbeResult(val status: ProbeStatus, val detail: String) {
        companion object {
            fun failed(detail: String) = ProbeResult(ProbeStatus.FAILED, detail)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PROBE_RETRY_DELAY_MS = 450L
        private const val LEASE_API_RETRY_BASE_MS = 2_000L
        private const val LEASE_REFRESH_BACKOFF_MS = 2_500L
        private const val MAX_INITIAL_LEASE_FAILURES = 4
        private const val ROUTE_FAILURES_BEFORE_REFRESH = 4
        private const val PROXY_CONNECT_TIMEOUT_SECONDS = 7L
        private const val PROXY_READ_TIMEOUT_SECONDS = 12L
        private const val PROXY_CALL_TIMEOUT_SECONDS = 20L
        private const val MAX_LOG_LINES = 80
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query MysteriumLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
