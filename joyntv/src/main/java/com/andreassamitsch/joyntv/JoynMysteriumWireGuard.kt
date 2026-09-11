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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped WireGuard tunnel used for Mysterium Residential.
 *
 * Mysterium's current client obtains a normal WireGuard configuration from /connection/connect.
 * Android's WireGuard backend understands IncludedApplications in the wg-quick [Interface]
 * section. We inject this APK's package name so only Joyn TV is routed through Mysterium; other
 * apps and the Android TV system stay on the normal connection.
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
                val configText = materializeConfig(
                    template = configTemplate,
                    privateKey = keys.privateKey.toBase64(),
                    packageName = appContext.packageName,
                )
                val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
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

    private fun materializeConfig(template: String, privateKey: String, packageName: String): String {
        require(template.isNotBlank()) { "Mysterium hat keine WireGuard-Konfiguration geliefert." }
        val withKey = template.replace("%private_key%", privateKey)
        require(!withKey.contains("%private_key%")) { "WireGuard Private-Key-Platzhalter konnte nicht ersetzt werden." }

        val lines = withKey
            .replace("\r\n", "\n")
            .split('\n')
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("IncludedApplications", ignoreCase = true) ||
                    trimmed.startsWith("ExcludedApplications", ignoreCase = true)
            }
            .toMutableList()

        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        require(interfaceIndex >= 0) { "Ungültige Mysterium-WireGuard-Konfiguration: [Interface] fehlt." }
        lines.add(interfaceIndex + 1, "IncludedApplications = $packageName")
        return lines.joinToString("\n")
    }
}
