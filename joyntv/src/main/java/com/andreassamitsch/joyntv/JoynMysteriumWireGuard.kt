package com.andreassamitsch.joyntv

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped WireGuard tunnel used for Mysterium Residential.
 *
 * Mysterium's native Android client deliberately separates Android TUN routing from WireGuard peer
 * routing: the TUN captures IPv4 and IPv6, while the peer transports IPv4. The stock WireGuard
 * Android GoBackend used here derives Android TUN routes directly from peer AllowedIPs, so that
 * exact split cannot be represented without forking the backend. For this app-scoped tunnel we use
 * Android's equivalent leak-safe model instead: IPv4 is routed through WireGuard and IPv6 is not
 * enabled at all for this VPN. Android therefore blocks IPv6 for Joyn TV instead of allowing it to
 * fall through to the TV's underlying network.
 */
internal object JoynMysteriumWireGuard {
    private const val PREFS_NAME = "joyn_protocol"
    private const val KEY_PRIVATE = "mysterium_wireguard_private_key"
    private const val TUNNEL_NAME = "joyntv-mysterium"

    private val operationMutex = Mutex()

    @Volatile
    private var backendInstance: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    fun permissionIntent(activity: Activity): Intent? = GoBackend.VpnService.prepare(activity)

    fun publicKey(context: Context): String = keyPair(context.applicationContext).publicKey.toBase64()

    suspend fun connect(context: Context, configTemplate: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            operationMutex.withLock {
                val appContext = context.applicationContext
                val keys = keyPair(appContext)
                val configText = JoynMysteriumWireGuardConfig.materialize(
                    template = configTemplate,
                    privateKey = keys.privateKey.toBase64(),
                    packageName = appContext.packageName,
                )
                val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                JoynMysteriumWireGuardConfig.validate(parsed)

                val state = backend(appContext).setState(tunnel, Tunnel.State.UP, parsed)
                check(state == Tunnel.State.UP) { "WireGuard-Tunnel wurde nicht aktiviert ($state)" }
            }
        }
    }

    suspend fun disconnect(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            operationMutex.withLock {
                val backend = backend(context.applicationContext)
                if (backend.getState(tunnel) != Tunnel.State.DOWN) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            }
        }
    }

    suspend fun isConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching { backend(context.applicationContext).getState(tunnel) == Tunnel.State.UP }
            .getOrDefault(false)
    }

    private fun backend(context: Context): GoBackend {
        backendInstance?.let { return it }
        return synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }

    private fun keyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PRIVATE, "").orEmpty()
        if (stored.isNotBlank()) {
            runCatching { KeyPair(Key.fromBase64(stored)) }.getOrNull()?.let { return it }
        }

        val generated = KeyPair()
        prefs.edit().putString(KEY_PRIVATE, generated.privateKey.toBase64()).apply()
        return generated
    }
}

/**
 * Converts a Mysterium wg-quick template into the leak-safe configuration consumed by GoBackend.
 *
 * Important Android VpnService behaviour: adding any IPv6 address, route or DNS server implicitly
 * enables IPv6 for that VPN. If the VPN does not then route IPv6, traffic may use the underlying
 * network. Therefore simply changing AllowedIPs is not sufficient: every IPv6 interface address
 * and IPv6 DNS server must also be removed before GoBackend creates VpnService.Builder.
 */
internal object JoynMysteriumWireGuardConfig {
    private enum class Section { NONE, INTERFACE, PEER }

    fun materialize(template: String, privateKey: String, packageName: String): String {
        require(template.isNotBlank()) { "Mysterium hat keine WireGuard-Konfiguration geliefert." }
        require(privateKey.isNotBlank()) { "WireGuard Private Key fehlt." }
        require(packageName.isNotBlank()) { "Android-Paketname fehlt." }

        val withKey = template.replace("%private_key%", privateKey)
        require(!withKey.contains("%private_key%")) {
            "WireGuard Private-Key-Platzhalter konnte nicht ersetzt werden."
        }

        val sourceLines = withKey
            .replace("\r\n", "\n")
            .split('\n')

        val peerCount = sourceLines.count { it.trim().equals("[Peer]", ignoreCase = true) }
        require(peerCount == 1) {
            "Ungültige Mysterium-WireGuard-Konfiguration: erwartet genau einen Peer, erhalten $peerCount."
        }

        var section = Section.NONE
        val lines = mutableListOf<String>()
        sourceLines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.equals("[Interface]", ignoreCase = true) -> {
                    section = Section.INTERFACE
                    lines += line
                }
                trimmed.equals("[Peer]", ignoreCase = true) -> {
                    section = Section.PEER
                    lines += line
                }
                section == Section.INTERFACE && keyEquals(line, "IncludedApplications") -> Unit
                section == Section.INTERFACE && keyEquals(line, "ExcludedApplications") -> Unit
                section == Section.INTERFACE && keyEquals(line, "Address") -> {
                    sanitizeAddressLine(line)?.let(lines::add)
                }
                section == Section.INTERFACE && keyEquals(line, "DNS") -> {
                    sanitizeDnsLine(line)?.let(lines::add)
                }
                section == Section.PEER && keyEquals(line, "AllowedIPs") -> Unit
                else -> lines += line
            }
        }

        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        require(interfaceIndex >= 0) {
            "Ungültige Mysterium-WireGuard-Konfiguration: [Interface] fehlt."
        }
        lines.add(interfaceIndex + 1, "IncludedApplications = $packageName")

        val peerIndex = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
        require(peerIndex >= 0) {
            "Ungültige Mysterium-WireGuard-Konfiguration: [Peer] fehlt."
        }

        // One real IPv4 default route is intentional here. GoBackend then sees a single peer with
        // a /0 route and does not call allowFamily(AF_INET6). Because the interface was sanitized
        // above and contains no IPv6 address/DNS/route, Android blocks IPv6 for this app-scoped VPN.
        lines.add(peerIndex + 1, "AllowedIPs = 0.0.0.0/0")

        return lines.joinToString("\n")
    }

    fun validate(config: Config) {
        val peers = config.getPeers()
        require(peers.size == 1) {
            "WireGuard-Sicherheitsprüfung: erwartet genau einen Peer, erhalten ${peers.size}."
        }

        val interfaceConfig = config.getInterface()
        require(interfaceConfig.getAddresses().none { it.getAddress() is Inet6Address }) {
            "WireGuard-Sicherheitsprüfung: IPv6-Adresse im VPN-Interface erkannt."
        }
        require(interfaceConfig.getDnsServers().none { it is Inet6Address }) {
            "WireGuard-Sicherheitsprüfung: IPv6-DNS im VPN-Interface erkannt."
        }

        val allowed = peers.single().getAllowedIps()
        require(allowed.none { it.getAddress() is Inet6Address }) {
            "WireGuard-Sicherheitsprüfung: IPv6-Route im Peer erkannt."
        }
        require(
            allowed.size == 1 &&
                allowed.single().getAddress() is Inet4Address &&
                allowed.single().getMask() == 0
        ) {
            "WireGuard-Sicherheitsprüfung: IPv4-Default-Route 0.0.0.0/0 fehlt."
        }
    }

    private fun sanitizeAddressLine(line: String): String? {
        val ipv4 = values(line).filter { token ->
            isIpv4Literal(token.substringBefore('/').trim())
        }
        return ipv4.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Address = ")
    }

    private fun sanitizeDnsLine(line: String): String? {
        // IPv4 DNS servers are kept. A non-IP hostname is a wg-quick DNS search domain and is safe
        // because GoBackend adds it as a search domain, not as an IPv6 route/address. Any token that
        // contains ':' is an IPv6 literal and is deliberately removed.
        val safe = values(line).filter { token -> !token.contains(':') }
        return safe.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "DNS = ")
    }

    private fun values(line: String): List<String> {
        val equals = line.indexOf('=')
        if (equals < 0) return emptyList()
        return line.substring(equals + 1)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun keyEquals(line: String, expected: String): Boolean {
        val equals = line.indexOf('=')
        if (equals < 0) return false
        return line.substring(0, equals).trim().equals(expected, ignoreCase = true)
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
