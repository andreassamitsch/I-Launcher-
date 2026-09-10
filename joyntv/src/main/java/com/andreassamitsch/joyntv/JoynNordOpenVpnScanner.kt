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
 * Sequentially scans Nord OpenVPN endpoints and keeps the first exit that passes Joyn Live.
 * UDP is preferred because Nord documents it as the default manual OpenVPN transport. TCP is used
 * as a fallback when the UDP attempt for a host cannot establish a usable tunnel.
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
        onProgress(
            JoynProxyDiscoveryProgress(
                "Lade aktuelle NordVPN OpenVPN-UDP/TCP-Server für ${country.name} …",
            ),
        )
        val allServers = loader.loadServers(country)
        if (allServers.isEmpty()) {
            return JoynNordTunnelDiscoveryResult(
                false,
                message = "NordVPN-API lieferte keine OpenVPN-Server für ${country.name}. · $credentialFingerprint",
            )
        }

        val servers = allServers
            .distinctBy { server -> server.station.takeIf(String::isNotBlank) ?: server.hostname }
            .take(MAX_SERVER_HOSTS_TO_SCAN)

        val seenExits = linkedSetOf<String>()
        val failures = linkedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var attemptedConnections = 0
        var hostsChecked = 0
        var udpAttempts = 0
        var tcpAttempts = 0
        var vpnDetected = 0
        var authFailures = 0
        var consecutiveAuthFailures = 0
        var successfulTunnels = 0
        var stoppedAtExitLimit = false

        fun fail(stage: String, text: String) {
            failures[stage] = (failures[stage] ?: 0) + 1
            if (examples.size < MAX_EXAMPLES) examples += "[$stage] ${text.take(220)}"
        }

        serverLoop@ for ((serverIndex, server) in servers.withIndex()) {
            if (seenExits.size >= MAX_UNIQUE_EXITS_TO_TEST) {
                stoppedAtExitLimit = true
                break
            }
            hostsChecked++

            val transports = buildList {
                if (server.supportsUdp) add(JoynNordOpenVpnTransport.UDP)
                if (server.supportsTcp) add(JoynNordOpenVpnTransport.TCP)
            }
            if (transports.isEmpty()) {
                fail("PROFIL", "${server.hostname}: Nord meldet weder openvpn_udp noch openvpn_tcp")
                continue
            }

            for (transport in transports) {
                if (seenExits.size >= MAX_UNIQUE_EXITS_TO_TEST) {
                    stoppedAtExitLimit = true
                    break@serverLoop
                }

                attemptedConnections++
                when (transport) {
                    JoynNordOpenVpnTransport.UDP -> udpAttempts++
                    JoynNordOpenVpnTransport.TCP -> tcpAttempts++
                }
                val transportLabel = transport.name

                onProgress(
                    JoynProxyDiscoveryProgress(
                        "OpenVPN ${serverIndex + 1}/${servers.size}: ${server.hostname}" +
                            (if (server.recommended) " · Nord-Empfehlung" else "") +
                            " · $transportLabel-Profil laden · Exits ${seenExits.size}/$MAX_UNIQUE_EXITS_TO_TEST …",
                        serverIndex,
                        servers.size,
                    ),
                )

                val profile = runCatching { loader.loadProfile(server, transport) }.getOrElse { error ->
                    fail("PROFIL-$transportLabel", "${server.hostname}: ${summarize(error)}")
                    continue
                }

                onProgress(
                    JoynProxyDiscoveryProgress(
                        "${server.hostname} · $transportLabel → ${profile.remoteHost}:${profile.remotePort} verbinden …",
                        serverIndex,
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
                            append("${server.hostname}/$transportLabel: ${state.message}")
                            if (logTail.isNotBlank()) append(" · $logTail")
                        }
                        fail("OPENVPN-$transportLabel", detail)
                        if (detail.contains("AUTH_FAILED", ignoreCase = true)) {
                            authFailures++
                            consecutiveAuthFailures++

                            // Nord's current fleet can reject valid service credentials on one
                            // endpoint and accept them on another. A confirmed HTTPS/89 login or
                            // any successful OpenVPN tunnel therefore disables a global auth abort.
                            val mayAbortGlobally = successfulTunnels == 0 &&
                                httpsCredentialCheck?.status != JoynNordHttpsCredentialStatus.CONFIRMED
                            val abortLimit = if (
                                httpsCredentialCheck?.status == JoynNordHttpsCredentialStatus.REJECTED
                            ) {
                                MAX_AUTH_FAILURES_IF_PROXY_REJECTED
                            } else {
                                MAX_CONSECUTIVE_AUTH_FAILURES_IF_UNCONFIRMED
                            }
                            if (mayAbortGlobally && consecutiveAuthFailures >= abortLimit) {
                                disconnectAndWait()
                                return result(
                                    connected = false,
                                    attemptedConnections = attemptedConnections,
                                    hostsChecked = hostsChecked,
                                    uniqueExits = seenExits.size,
                                    vpnDetected = vpnDetected,
                                    successfulTunnels = successfulTunnels,
                                    authFailures = authFailures,
                                    udpAttempts = udpAttempts,
                                    tcpAttempts = tcpAttempts,
                                    failures = failures,
                                    examples = examples,
                                    serverPool = allServers.size,
                                    stoppedAtExitLimit = false,
                                    messagePrefix = "NordVPN-Authentifizierung konnte nicht bestätigt werden. Suche vorsorglich abgebrochen. · $credentialFingerprint",
                                )
                            }
                        } else {
                            consecutiveAuthFailures = 0
                        }
                        disconnectAndWait()
                        // Try the other transport for this same host when available.
                        continue
                    }

                    is JoynNordTunnelState.Connected -> {
                        successfulTunnels++
                        consecutiveAuthFailures = 0
                    }

                    else -> {
                        consecutiveAuthFailures = 0
                        fail(
                            "TIMEOUT-$transportLabel",
                            "${server.hostname}: nach ${CONNECT_TIMEOUT_MS / 1000}s kein OpenVPN Connected",
                        )
                        disconnectAndWait()
                        continue
                    }
                }

                // Give Android a moment to make the new TUN route observable to fresh sockets.
                delay(ROUTE_SETTLE_MS)
                val exit = joynProbe.readExit().getOrElse { error ->
                    fail("EXIT-$transportLabel", "${server.hostname}: ${summarize(error)}")
                    disconnectAndWait()
                    // A second transport can still be useful if route setup failed.
                    continue
                }

                if (!seenExits.add(exit.ip)) {
                    fail("DUPLIKAT", "${server.hostname}: Exit ${exit.ip} wurde bereits gegen Joyn geprüft")
                    onProgress(
                        JoynProxyDiscoveryProgress(
                            "${server.hostname}: Exit ${exit.ip} bereits geprüft → nächster Server",
                            serverIndex + 1,
                            servers.size,
                        ),
                    )
                    disconnectAndWait()
                    // UDP and TCP on the same host normally expose the same exit. Do not retry it.
                    continue@serverLoop
                }

                if (!exit.country.equals(country.name, ignoreCase = true)) {
                    fail("LAND", "${server.hostname}: Exit=${exit.country}/${exit.ip}, erwartet=${country.name}")
                    disconnectAndWait()
                    continue@serverLoop
                }

                onProgress(
                    JoynProxyDiscoveryProgress(
                        "${server.hostname} · $transportLabel: Exit ${exit.ip} (${exit.country}) · Joyn Live prüfen …",
                        serverIndex,
                        servers.size,
                    ),
                )
                when (val gate = joynProbe.run(country, apiKey, exit)) {
                    is JoynNordTunnelGateResult.Success -> {
                        rememberActive(server.hostname, gate.exitIp)
                        val message =
                            "NordVPN OpenVPN funktioniert mit Joyn Live: ${server.hostname} · $transportLabel · Exit ${gate.exitIp} · ${gate.latencyMs} ms. Tunnel bleibt aktiv. " +
                                "OpenVPN verbunden=$successfulTunnels · AUTH_FAILED=$authFailures · UDP=$udpAttempts · TCP=$tcpAttempts."
                        onProgress(JoynProxyDiscoveryProgress(message, serverIndex + 1, servers.size))
                        return JoynNordTunnelDiscoveryResult(
                            connected = true,
                            host = server.hostname,
                            exitIp = gate.exitIp,
                            attemptedServers = attemptedConnections,
                            uniqueExits = seenExits.size,
                            vpnDetected = vpnDetected,
                            message = message,
                        )
                    }

                    is JoynNordTunnelGateResult.Failed -> {
                        if (gate.vpnDetected) vpnDetected++
                        fail(
                            if (gate.vpnDetected) "VPN" else gate.stage,
                            "${server.hostname}/$transportLabel · Exit ${gate.exitIp ?: exit.ip}: ${gate.detail}",
                        )
                        onProgress(
                            JoynProxyDiscoveryProgress(
                                if (gate.vpnDetected) {
                                    "${server.hostname}: Joyn erkennt Exit ${exit.ip} als VPN → nächster Server"
                                } else {
                                    "${server.hostname}: ${gate.stage} fehlgeschlagen → nächster Server"
                                },
                                serverIndex + 1,
                                servers.size,
                            ),
                        )
                        disconnectAndWait()
                        // The exit is already known and tested. Trying TCP after a successful UDP
                        // tunnel (or vice versa) would normally just test the same public IP again.
                        continue@serverLoop
                    }
                }
            }
        }

        clearActive()
        disconnectAndWait()
        return result(
            connected = false,
            attemptedConnections = attemptedConnections,
            hostsChecked = hostsChecked,
            uniqueExits = seenExits.size,
            vpnDetected = vpnDetected,
            successfulTunnels = successfulTunnels,
            authFailures = authFailures,
            udpAttempts = udpAttempts,
            tcpAttempts = tcpAttempts,
            failures = failures,
            examples = examples,
            serverPool = allServers.size,
            stoppedAtExitLimit = stoppedAtExitLimit,
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
        attemptedConnections: Int,
        hostsChecked: Int,
        uniqueExits: Int,
        vpnDetected: Int,
        successfulTunnels: Int,
        authFailures: Int,
        udpAttempts: Int,
        tcpAttempts: Int,
        failures: Map<String, Int>,
        examples: List<String>,
        serverPool: Int,
        stoppedAtExitLimit: Boolean,
        messagePrefix: String,
    ): JoynNordTunnelDiscoveryResult {
        val stats = failures.entries.joinToString(" · ") { (stage, count) -> "$stage=$count" }
        val exampleText = if (examples.isEmpty()) "" else "\nBeispiele:\n${examples.joinToString("\n")}" 
        val limitText = if (stoppedAtExitLimit) {
            " · Sicherheitslimit von $MAX_UNIQUE_EXITS_TO_TEST eindeutigen Exits erreicht"
        } else {
            ""
        }
        return JoynNordTunnelDiscoveryResult(
            connected = connected,
            attemptedServers = attemptedConnections,
            uniqueExits = uniqueExits,
            vpnDetected = vpnDetected,
            message = "$messagePrefix OpenVPN-Server verfügbar=$serverPool · Hosts geprüft=$hostsChecked · Verbindungsversuche=$attemptedConnections · UDP=$udpAttempts · TCP=$tcpAttempts · OpenVPN verbunden=$successfulTunnels · AUTH_FAILED=$authFailures · eindeutige Exits=$uniqueExits · VPN erkannt=$vpnDetected$limitText" +
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
        // Explore the whole current DE/AT/CH inventory, but stop once enough distinct public exits
        // have been rejected to avoid an excessively long unattended TV-side scan.
        private const val MAX_SERVER_HOSTS_TO_SCAN = 350
        private const val MAX_UNIQUE_EXITS_TO_TEST = 40
        private const val MAX_EXAMPLES = 12
        private const val MAX_AUTH_FAILURES_IF_PROXY_REJECTED = 4
        private const val MAX_CONSECUTIVE_AUTH_FAILURES_IF_UNCONFIRMED = 12
        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val STOP_TIMEOUT_MS = 6_000L
        private const val ROUTE_SETTLE_MS = 800L
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ACTIVE = "nord_openvpn_active"
        private const val KEY_HOST = "nord_openvpn_host"
        private const val KEY_EXIT_IP = "nord_openvpn_exit_ip"
    }
}
