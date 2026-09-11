package com.andreassamitsch.joyntv

import android.content.Context
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
 * Finds a Mysterium Residential exit that Joyn accepts.
 *
 * Despite the historical class name, this no longer consumes Mysterium's connect-proxy gateway.
 * The current Mysterium client uses /connection/connect to obtain a WireGuard configuration, then
 * establishes an Android VPN tunnel. We do the same and restrict the tunnel to this Joyn TV APK.
 */
internal class JoynMysteriumProxyScanner(
    context: Context,
    private val apiClient: JoynMysteriumApiClient,
) {
    private val appContext = context.applicationContext
    private val wireGuardApi = JoynMysteriumWireGuardApiClient(appContext, apiClient)

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

        // Configuration requests must be made outside a previous test tunnel. Once a candidate
        // succeeds we deliberately leave its app-scoped tunnel active.
        JoynMysteriumWireGuard.disconnect(appContext)

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

        val publicKey = runCatching { JoynMysteriumWireGuard.publicKey(appContext) }.getOrElse { error ->
            return@withContext JoynProxyDiscoveryResult(
                null,
                attemptsLimit,
                0,
                "WireGuard-Schlüssel konnte nicht erstellt werden: ${error.message ?: error.javaClass.simpleName}",
            )
        }

        val log = mutableListOf<String>()
        log += "API: ${apiStatus.message}"
        log += apiClient.residentialLocationSummary()
        log += "Transport: Mysterium WireGuard · Android VPN · nur Joyn TV"
        if (!allTraffic) {
            log += "Hinweis: Die frühere Option 'Nur API / Token' entfällt bei WireGuard; innerhalb von Joyn TV läuft der gesamte Traffic durch den Tunnel."
        }

        val seenExits = linkedSetOf<String>()
        val seenConfigs = linkedSetOf<String>()
        var attempted = 0
        var configsReceived = 0
        var duplicates = 0
        var wrongCountry = 0
        var tunnelFailures = 0
        var joynVpnDetected = 0
        var joynOtherFailure = 0

        while (attempted < attemptsLimit && !JoynMysteriumScanControl.isStopRequested()) {
            attempted++
            JoynMysteriumWireGuard.disconnect(appContext)

            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Mysterium Residential ${country.name}: fordere WireGuard-IP $attempted/$attemptsLimit an …\n" +
                        shortStats(configsReceived, seenExits.size, joynVpnDetected, tunnelFailures),
                    attempted = attempted - 1,
                    total = attemptsLimit,
                ),
            )

            val lease = wireGuardApi.requestResidential(
                country = country,
                publicKey = publicKey,
                resetConnection = true,
            ).getOrElse { error ->
                val reason = error.message ?: error.javaClass.simpleName
                log += "#$attempted CONFIG_FEHLER · $reason"
                if (reason.contains("limit", ignoreCase = true)) {
                    JoynMysteriumWireGuard.disconnect(appContext)
                    return@withContext finish(
                        activated = false,
                        country = country,
                        attemptsLimit = attemptsLimit,
                        attempted = attempted,
                        stopped = false,
                        configsReceived = configsReceived,
                        uniqueExits = seenExits.size,
                        duplicates = duplicates,
                        wrongCountry = wrongCountry,
                        tunnelFailures = tunnelFailures,
                        joynVpnDetected = joynVpnDetected,
                        joynOtherFailure = joynOtherFailure,
                        log = log,
                        extra = "Mysterium meldet ein IP-Wechsel-Limit; Scan wurde beendet.",
                    )
                }
                delay(RETRY_DELAY_MS)
                continue
            }
            configsReceived++

            val configKey = listOf(lease.id, lease.providerHash, lease.exitIp, lease.config.hashCode().toString())
                .joinToString(":")
            if (!seenConfigs.add(configKey)) {
                duplicates++
                log += "#$attempted CONFIG_DUPLIKAT · ${lease.exitIp.ifBlank { lease.id }}"
                delay(RETRY_DELAY_MS)
                continue
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Mysterium Residential ${country.name}: starte app-eigenen WireGuard-Tunnel …",
                    attempted = attempted,
                    total = attemptsLimit,
                ),
            )

            val connectResult = JoynMysteriumWireGuard.connect(appContext, lease.config)
            if (connectResult.isFailure) {
                tunnelFailures++
                val error = connectResult.exceptionOrNull()
                log += "#$attempted TUNNEL_FEHLER · ${error?.javaClass?.simpleName ?: "Fehler"}: ${error?.message.orEmpty()}"
                JoynMysteriumWireGuard.disconnect(appContext)
                delay(RETRY_DELAY_MS)
                continue
            }

            val client = tunneledClient()
            val trace = resolveExit(client)
            if (trace == null) {
                tunnelFailures++
                log += "#$attempted TUNNEL_FEHLER · WireGuard aktiv, Exit-IP über Tunnel nicht ermittelbar"
                JoynMysteriumWireGuard.disconnect(appContext)
                delay(RETRY_DELAY_MS)
                continue
            }

            if (!trace.country.equals(country.name, ignoreCase = true)) {
                wrongCountry++
                log += "#$attempted FALSCHES_LAND · ${trace.ip} · ${trace.country} statt ${country.name}"
                JoynMysteriumWireGuard.disconnect(appContext)
                delay(RETRY_DELAY_MS)
                continue
            }

            if (!seenExits.add(trace.ip)) {
                duplicates++
                log += "#$attempted EXIT_DUPLIKAT · ${trace.ip}"
                JoynMysteriumWireGuard.disconnect(appContext)
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
                ProbeStatus.OK -> {
                    log += "#$attempted OK · ${trace.ip} · Joyn Live freigegeben · ${latencyMs} ms"
                    return@withContext finish(
                        activated = true,
                        country = country,
                        attemptsLimit = attemptsLimit,
                        attempted = attempted,
                        stopped = false,
                        configsReceived = configsReceived,
                        uniqueExits = seenExits.size,
                        duplicates = duplicates,
                        wrongCountry = wrongCountry,
                        tunnelFailures = tunnelFailures,
                        joynVpnDetected = joynVpnDetected,
                        joynOtherFailure = joynOtherFailure,
                        log = log,
                        extra = "Treffer: Residential-Exit ${trace.ip} besteht Joyns Live-Freigabe. App-eigener WireGuard-Tunnel bleibt aktiv.",
                    )
                }
                ProbeStatus.VPN_DETECTED -> {
                    joynVpnDetected++
                    log += "#$attempted VPN_ERKANNT · ${trace.ip} · ${probe.detail}"
                }
                ProbeStatus.FAILED -> {
                    joynOtherFailure++
                    log += "#$attempted JOYN_FEHLER · ${trace.ip} · ${probe.detail}"
                }
            }

            JoynMysteriumWireGuard.disconnect(appContext)
            delay(RETRY_DELAY_MS)
        }

        JoynMysteriumWireGuard.disconnect(appContext)
        finish(
            activated = false,
            country = country,
            attemptsLimit = attemptsLimit,
            attempted = attempted,
            stopped = JoynMysteriumScanControl.isStopRequested(),
            configsReceived = configsReceived,
            uniqueExits = seenExits.size,
            duplicates = duplicates,
            wrongCountry = wrongCountry,
            tunnelFailures = tunnelFailures,
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

    private fun tunneledClient(): OkHttpClient = OkHttpClient.Builder()
        // NO_PROXY only disables Java HTTP proxies. Android's app-scoped VPN route remains active.
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
            if (ip.isBlank() || country.isBlank()) null else ExitTrace(ip, country)
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
            if (!response.isSuccessful) return@runCatching ProbeResult.failed("Entitlement HTTP ${response.code}: ${compact(body)}")
            val entitlement = runCatching { JSONObject(body).optString("entitlement_token") }.getOrDefault("")
            if (entitlement.isNotBlank()) ProbeResult(ProbeStatus.OK, "Live freigegeben")
            else ProbeResult.failed("Entitlement ohne Token: ${compact(body)}")
        }
    }.getOrElse { error -> ProbeResult.failed("Entitlement ${error.javaClass.simpleName}: ${error.message.orEmpty()}") }

    private fun finish(
        activated: Boolean,
        country: JoynCountry,
        attemptsLimit: Int,
        attempted: Int,
        stopped: Boolean,
        configsReceived: Int,
        uniqueExits: Int,
        duplicates: Int,
        wrongCountry: Int,
        tunnelFailures: Int,
        joynVpnDetected: Int,
        joynOtherFailure: Int,
        log: List<String>,
        extra: String,
    ): JoynProxyDiscoveryResult {
        val message = buildString {
            append(if (activated) "Mysterium Residential erfolgreich" else if (stopped) "Mysterium-Test gestoppt" else "Mysterium-Test abgeschlossen")
            append(" · ${country.name}\n")
            append("Versuche=$attempted/$attemptsLimit · VPN-Configs=$configsReceived · eindeutige Exits=$uniqueExits")
            append(" · Joyn VPN erkannt=$joynVpnDetected")
            append(" · sonstige Joyn-Fehler=$joynOtherFailure")
            append(" · Tunnel-Fehler=$tunnelFailures · falsches Land=$wrongCountry · Duplikate=$duplicates\n")
            append(extra)
            if (log.isNotEmpty()) {
                append("\n\nTestlog:\n")
                append(log.takeLast(MAX_LOG_LINES).joinToString("\n"))
                if (log.size > MAX_LOG_LINES) append("\n… ${log.size - MAX_LOG_LINES} ältere Einträge ausgeblendet")
            }
        }
        return JoynProxyDiscoveryResult(
            config = null,
            candidates = attemptsLimit,
            attempted = attempted,
            message = message,
            activated = activated,
        )
    }

    private fun shortStats(configs: Int, exits: Int, vpn: Int, tunnelFailures: Int): String =
        "VPN-Configs=$configs · Exits=$exits · VPN erkannt=$vpn · Tunnel-Fehler=$tunnelFailures"

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(220)

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
        private const val RETRY_DELAY_MS = 450L
        private const val NETWORK_CONNECT_TIMEOUT_SECONDS = 10L
        private const val NETWORK_READ_TIMEOUT_SECONDS = 15L
        private const val NETWORK_CALL_TIMEOUT_SECONDS = 25L
        private const val MAX_LOG_LINES = 80
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query MysteriumLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
