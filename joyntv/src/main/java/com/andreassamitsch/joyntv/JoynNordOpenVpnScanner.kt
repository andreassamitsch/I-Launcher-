package com.andreassamitsch.joyntv

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class JoynNordTunnelDiscoveryResult(
    val connected: Boolean,
    val host: String? = null,
    val exitIp: String? = null,
    val attemptedServers: Int = 0,
    val uniqueExits: Int = 0,
    val vpnDetected: Int = 0,
    val message: String,
)

/**
 * Sequentially connects normal Nord OpenVPN TCP servers and keeps the first exit that passes Joyn
 * Live entitlement. Exit IPs are deduplicated before the expensive Joyn probe.
 */
internal class JoynNordOpenVpnScanner(context: Context) {
    private val appContext = context.applicationContext
    private val loader = JoynNordOpenVpnProfileLoader(appContext)
    private val joynProbe = JoynNordTunnelJoynProbe()
    private val httpsCredentialDiagnostics = JoynNordHttpsCredentialDiagnostics()

    suspend fun findBest(
        country: JoynCountry,
        apiKey: String?,
        username: String,
        password: String,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynNordTunnelDiscoveryResult {
        if (username.isBlank() || password.isBlank()) {
            return JoynNordTunnelDiscoveryResult(false, message = "NordVPN-Service-Credentials fehlen.")
        }
        if (apiKey.isNullOrBlank()) {
            return JoynNordTunnelDiscoveryResult(
                false,
                message = "Der Joyn API-Schlüssel für ${country.name} fehlt. Joyn bitte einmal ohne Proxy/VPN starten.",
            )
        }

        val credentialFingerprint = JoynNordCredentialFingerprint.describe(username, password)
        onProgress(JoynProxyDiscoveryProgress("Prüfe dieselben Nord-Credentials zuerst kurz auf HTTPS/89 …"))
        val httpsCredentialCheck = runCatching {
            httpsCredentialDiagnostics.run(country, username, password)
        }.getOrNull()

        disconnectAndWait()
        onProgress(JoynProxyDiscoveryProgress("Lade normale NordVPN OpenVPN-TCP-Server für ${country.name} …"))
        val allServers = loader.loadServers(country)
        if (allServers.isEmpty()) {
            return JoynNordTunnelDiscoveryResult(
                false,
                message = "NordVPN-API lieferte keine OpenVPN-TCP-Server für ${country.name}. · $credentialFingerprint",
            )
        }

        val servers = allServers
            .distinctBy { server -> server.station.takeIf(String::isNotBlank) ?: server.hostname }
            .take(MAX_SERVERS_TO_TEST)
        val seenExits = linkedSetOf<String>()
        val failures = linkedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var attempted = 0
        var vpnDetected = 0
        var authFailures = 0

        fun fail(stage: String, text: String) {
            failures[stage] = (failures[stage] ?: 0) + 1
            if (examples.size < MAX_EXAMPLES) examples += "[$stage] ${text.take(220)}"
        }

        for ((index, server) in servers.withIndex()) {
            attempted++
            onProgress(
                JoynProxyDiscoveryProgress(
                    "OpenVPN ${index + 1}/${servers.size}: ${server.hostname} · Profil laden …",
                    index,
                    servers.size,
                ),
            )

            val profile = runCatching { loader.loadProfile(server) }.getOrElse { error ->
                fail("PROFIL", "${server.hostname}: ${summarize(error)}")
                continue
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    "OpenVPN ${index + 1}/${servers.size}: ${server.hostname} → ${profile.remoteHost}:${profile.remotePort} verbinden …",
                    index,
                    servers.size,
                ),
            )
            JoynNordOpenVpnService.connect(appContext, profile, username, password)

            val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                JoynNordTunnelRuntime.state
                    .filter { value ->
                        (value is JoynNordTunnelState.Connected && value.host == server.hostname) ||
                            (value is JoynNordTunnelState.Error && value.host == server.hostname)
                    }
                    .first()
            }

            when (state) {
                is JoynNordTunnelState.Error -> {
                    val logTail = JoynNordTunnelRuntime.logs.value.takeLast(3).joinToString(" | ")
                    val detail = buildString {
                        append("${server.hostname}: ${state.message}")
                        if (logTail.isNotBlank()) append(" · $logTail")
                    }
                    fail("OPENVPN", detail)
                    if (detail.contains("AUTH_FAILED", ignoreCase = true)) {
                        authFailures++
                        if (authFailures >= MAX_AUTH_FAILURES_BEFORE_ABORT) {
                            disconnectAndWait()
                            val credentialConclusion = when (httpsCredentialCheck?.status) {
                                JoynNordHttpsCredentialStatus.CONFIRMED ->
                                    "Dieselben Credential-Bytes wurden unmittelbar davor vom Nord-HTTPS/89-Proxy akzeptiert, OpenVPN lehnt sie aber mit AUTH_FAILED ab. Das spricht gegen einen Eingabefehler und für unterschiedliche Nord-Authentifizierung/Service-Berechtigungen."
                                JoynNordHttpsCredentialStatus.REJECTED ->
                                    "Auch der unmittelbare HTTPS/89-Gegentest hat diese Credential-Bytes mit 407 abgelehnt. Credentials im Nord Account erneut prüfen."
                                else ->
                                    "Der parallele HTTPS/89-Credential-Gegentest war nicht eindeutig."
                            }
                            return result(
                                connected = false,
                                attempted = attempted,
                                uniqueExits = seenExits.size,
                                vpnDetected = vpnDetected,
                                failures = failures,
                                examples = examples,
                                serverPool = allServers.size,
                                messagePrefix = "NordVPN OpenVPN lehnt die Service-Credentials wiederholt mit AUTH_FAILED ab. Suche abgebrochen. $credentialConclusion · $credentialFingerprint",
                            )
                        }
                    }
                    disconnectAndWait()
                    continue
                }
                is JoynNordTunnelState.Connected -> Unit
                else -> {
                    fail("TIMEOUT", "${server.hostname}: nach ${CONNECT_TIMEOUT_MS / 1000}s kein OpenVPN Connected")
                    disconnectAndWait()
                    continue
                }
            }

            // Give Android a short moment to make the newly established TUN route observable to
            // fresh sockets before checking the public exit.
            delay(ROUTE_SETTLE_MS)
            val exit = joynProbe.readExit().getOrElse { error ->
                fail("EXIT", "${server.hostname}: ${summarize(error)}")
                disconnectAndWait()
                continue
            }

            if (!seenExits.add(exit.ip)) {
                fail("DUPLIKAT", "${server.hostname}: Exit ${exit.ip} wurde bereits gegen Joyn geprüft")
                onProgress(
                    JoynProxyDiscoveryProgress(
                        "${server.hostname}: Exit ${exit.ip} bereits geprüft → nächster Server",
                        index + 1,
                        servers.size,
                    ),
                )
                disconnectAndWait()
                continue
            }

            if (!exit.country.equals(country.name, ignoreCase = true)) {
                fail("LAND", "${server.hostname}: Exit=${exit.country}/${exit.ip}, erwartet=${country.name}")
                disconnectAndWait()
                continue
            }

            onProgress(
                JoynProxyDiscoveryProgress(
                    "${server.hostname}: Exit ${exit.ip} (${exit.country}) · Joyn Live prüfen …",
                    index,
                    servers.size,
                ),
            )
            when (val gate = joynProbe.run(country, apiKey, exit)) {
                is JoynNordTunnelGateResult.Success -> {
                    rememberActive(server.hostname, gate.exitIp)
                    val message =
                        "NordVPN OpenVPN funktioniert mit Joyn Live: ${server.hostname} · Exit ${gate.exitIp} · ${gate.latencyMs} ms. Tunnel bleibt aktiv."
                    onProgress(JoynProxyDiscoveryProgress(message, index + 1, servers.size))
                    return JoynNordTunnelDiscoveryResult(
                        connected = true,
                        host = server.hostname,
                        exitIp = gate.exitIp,
                        attemptedServers = attempted,
                        uniqueExits = seenExits.size,
                        vpnDetected = vpnDetected,
                        message = message,
                    )
                }
                is JoynNordTunnelGateResult.Failed -> {
                    if (gate.vpnDetected) vpnDetected++
                    fail(
                        if (gate.vpnDetected) "VPN" else gate.stage,
                        "${server.hostname} · Exit ${gate.exitIp ?: exit.ip}: ${gate.detail}",
                    )
                    onProgress(
                        JoynProxyDiscoveryProgress(
                            if (gate.vpnDetected) {
                                "${server.hostname}: Joyn erkennt Exit ${exit.ip} als VPN → nächster Server"
                            } else {
                                "${server.hostname}: ${gate.stage} fehlgeschlagen → nächster Server"
                            },
                            index + 1,
                            servers.size,
                        ),
                    )
                    disconnectAndWait()
                }
            }
        }

        clearActive()
        disconnectAndWait()
        return result(
            connected = false,
            attempted = attempted,
            uniqueExits = seenExits.size,
            vpnDetected = vpnDetected,
            failures = failures,
            examples = examples,
            serverPool = allServers.size,
            messagePrefix = "Kein getesteter normaler NordVPN-OpenVPN-Exit für ${country.name} bestand Joyn Live. · $credentialFingerprint",
        )
    }

    suspend fun disconnect() {
        clearActive()
        disconnectAndWait()
    }

    private suspend fun disconnectAndWait() {
        val current = JoynNordTunnelRuntime.state.value
        if (current is JoynNordTunnelState.Idle) return
        JoynNordOpenVpnService.disconnect(appContext)
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            JoynNordTunnelRuntime.state.filter { it is JoynNordTunnelState.Idle }.first()
        }
        delay(250)
    }

    private fun result(
        connected: Boolean,
        attempted: Int,
        uniqueExits: Int,
        vpnDetected: Int,
        failures: Map<String, Int>,
        examples: List<String>,
        serverPool: Int,
        messagePrefix: String,
    ): JoynNordTunnelDiscoveryResult {
        val stats = failures.entries.joinToString(" · ") { (stage, count) -> "$stage=$count" }
        val exampleText = if (examples.isEmpty()) "" else "\nBeispiele:\n${examples.joinToString("\n")}" 
        return JoynNordTunnelDiscoveryResult(
            connected = connected,
            attemptedServers = attempted,
            uniqueExits = uniqueExits,
            vpnDetected = vpnDetected,
            message = "$messagePrefix Normale OpenVPN-TCP-Server verfügbar=$serverPool · getestet=$attempted · eindeutige Exits=$uniqueExits · VPN erkannt=$vpnDetected" +
                (if (stats.isBlank()) "." else " · $stats.") + exampleText,
        )
    }

    private fun rememberActive(host: String, exitIp: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_HOST, host)
            .putString(KEY_EXIT_IP, exitIp)
            .apply()
    }

    private fun clearActive() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_HOST)
            .remove(KEY_EXIT_IP)
            .apply()
    }

    private fun summarize(error: Throwable): String {
        val text = error.message.orEmpty().replace('\r', ' ').replace('\n', ' ').trim().take(180)
        return if (text.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $text"
    }

    private companion object {
        private const val MAX_SERVERS_TO_TEST = 40
        private const val MAX_EXAMPLES = 10
        private const val MAX_AUTH_FAILURES_BEFORE_ABORT = 2
        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val STOP_TIMEOUT_MS = 6_000L
        private const val ROUTE_SETTLE_MS = 800L
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ACTIVE = "nord_openvpn_active"
        private const val KEY_HOST = "nord_openvpn_host"
        private const val KEY_EXIT_IP = "nord_openvpn_exit_ip"
    }
}
