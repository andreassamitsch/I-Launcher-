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
 * Discovery still uses the legacy process key. Persistent country profiles use one independent
 * WireGuard key per market. Android itself can only keep one VpnService tunnel active, therefore
 * switching countries replaces the local WireGuard configuration atomically as far as GoBackend
 * permits.
 */
internal object JoynMysteriumWireGuard {
    private const val PREFS_NAME = "joyn_protocol"
    private const val KEY_PRIVATE = "mysterium_wireguard_private_key"
    private const val TUNNEL_NAME = "joyntv-mysterium"

    private val operationMutex = Mutex()

    @Volatile
    private var backendInstance: GoBackend? = null

    @Volatile
    private var activeMarket: JoynCountry? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    fun permissionIntent(activity: Activity): Intent? = GoBackend.VpnService.prepare(activity)

    /** Legacy key used by the Residential scanner while it evaluates candidates. */
    fun publicKey(context: Context): String = keyPair(context.applicationContext, country = null).publicKey.toBase64()

    /** Stable per-country key used by persistent AT/DE/CH profiles. */
    fun publicKey(context: Context, country: JoynCountry): String =
        keyPair(context.applicationContext, country).publicKey.toBase64()

    fun activeCountry(): JoynCountry? = activeMarket

    fun adoptActiveCountry(country: JoynCountry) {
        activeMarket = country
    }

    suspend fun connect(context: Context, configTemplate: String): Result<Unit> =
        connect(context, country = null, configTemplate = configTemplate)

    suspend fun connect(
        context: Context,
        country: JoynCountry?,
        configTemplate: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            operationMutex.withLock {
                val appContext = context.applicationContext
                val keys = keyPair(appContext, country)
                val configText = JoynMysteriumWireGuardConfig.materialize(
                    template = configTemplate,
                    privateKey = keys.privateKey.toBase64(),
                    packageName = appContext.packageName,
                )
                val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                JoynMysteriumWireGuardConfig.validate(parsed)

                // GoBackend resolves DNS peer endpoints only after it has already torn the previous
                // tunnel down. Its resolver retries up to ten times with one-second sleeps. Resolve
                // the endpoint once while the old country is still usable and keep that answer in
                // InetEndpoint's cache, so the actual AT/DE/CH handover cannot enter that 10 s loop.
                JoynMysteriumWireGuardConfig.preResolvePeerEndpoint(parsed)

                val state = backend(appContext).setState(tunnel, Tunnel.State.UP, parsed)
                check(state == Tunnel.State.UP) { "WireGuard-Tunnel wurde nicht aktiviert ($state)" }
                if (country != null) activeMarket = country
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
                activeMarket = null
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

    private fun keyPair(context: Context, country: JoynCountry?): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferenceKey = country?.let { "${KEY_PRIVATE}_${it.name.lowercase()}" } ?: KEY_PRIVATE
        val stored = prefs.getString(preferenceKey, "").orEmpty()
        if (stored.isNotBlank()) {
            runCatching { KeyPair(Key.fromBase64(stored)) }.getOrNull()?.let { return it }
        }

        val generated = KeyPair()
        prefs.edit().putString(preferenceKey, generated.privateKey.toBase64()).apply()
        return generated
    }
}

/**
 * Converts a Mysterium wg-quick template into the leak-safe configuration consumed by GoBackend.
 *
 * Besides stripping IPv6, use deterministic public IPv4 DNS servers instead of carrying a stale
 * provider DNS route from one country session into the next. Mysterium itself supports public DNS
 * (including 1.1.1.1), while its Android mobile node uses MTU 1280 and a WireGuard keepalive of 18 s.
 * Those values avoid the DNS bootstrap race seen immediately after DE/AT/CH handovers.
 */
internal object JoynMysteriumWireGuardConfig {
    private enum class Section { NONE, INTERFACE, PEER }

    private const val SAFE_MTU = 1280
    private const val KEEPALIVE_SECONDS = 18
    private const val DNS_PRIMARY = "1.1.1.1"
    private const val DNS_SECONDARY = "8.8.8.8"

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
                section == Section.INTERFACE && keyEquals(line, "DNS") -> Unit
                section == Section.INTERFACE && keyEquals(line, "MTU") -> Unit
                section == Section.PEER && keyEquals(line, "AllowedIPs") -> Unit
                section == Section.PEER && keyEquals(line, "PersistentKeepalive") -> Unit
                else -> lines += line
            }
        }

        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        require(interfaceIndex >= 0) {
            "Ungültige Mysterium-WireGuard-Konfiguration: [Interface] fehlt."
        }
        lines.add(interfaceIndex + 1, "MTU = $SAFE_MTU")
        lines.add(interfaceIndex + 1, "DNS = $DNS_PRIMARY, $DNS_SECONDARY")
        lines.add(interfaceIndex + 1, "IncludedApplications = $packageName")

        val peerIndex = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
        require(peerIndex >= 0) {
            "Ungültige Mysterium-WireGuard-Konfiguration: [Peer] fehlt."
        }

        lines.add(peerIndex + 1, "PersistentKeepalive = $KEEPALIVE_SECONDS")
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
        require(interfaceConfig.getDnsServers().map { it.hostAddress }.toSet() == setOf(DNS_PRIMARY, DNS_SECONDARY)) {
            "WireGuard-Sicherheitsprüfung: erwartete IPv4-DNS-Server fehlen."
        }
        require(interfaceConfig.getMtu().orElse(SAFE_MTU) == SAFE_MTU) {
            "WireGuard-Sicherheitsprüfung: MTU muss $SAFE_MTU sein."
        }

        val peer = peers.single()
        val allowed = peer.getAllowedIps()
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
        require(peer.getPersistentKeepalive().orElse(0) == KEEPALIVE_SECONDS) {
            "WireGuard-Sicherheitsprüfung: PersistentKeepalive muss $KEEPALIVE_SECONDS s sein."
        }
    }

    fun preResolvePeerEndpoint(config: Config) {
        val endpoint = config.getPeers().single().getEndpoint().orElse(null) ?: return
        val resolved = endpoint.getResolved().orElse(null)
        require(resolved != null) {
            "Mysterium-WireGuard-Endpunkt ${endpoint.getHost()} konnte vor dem Länderwechsel nicht aufgelöst werden."
        }
    }

    private fun sanitizeAddressLine(line: String): String? {
        val ipv4 = values(line).filter { token ->
            isIpv4Literal(token.substringBefore('/').trim())
        }
        return ipv4.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Address = ")
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
