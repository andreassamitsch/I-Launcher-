package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Credentials
import okhttp3.OkHttpClient

internal enum class JoynProxyTransport {
    HTTP,
}

internal data class JoynProxyConfig(
    val enabled: Boolean = false,
    val automatic: Boolean = false,
    val transport: JoynProxyTransport = JoynProxyTransport.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val allTraffic: Boolean = false,
    val source: String = "",
    val latencyMs: Long = -1L,
    val lastVerifiedAtEpochMs: Long = 0L,
) {
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    val isMysterium: Boolean
        get() = source.startsWith("Mysterium", ignoreCase = true)
}

/**
 * Process-wide routing for the active residential HTTP proxy.
 *
 * The important detail is that the same [ProxySelector] instance stays installed for the whole
 * process lifetime. OkHttp snapshots ProxySelector.getDefault() when a client is built. Replacing
 * the system selector later therefore leaves already-created Joyn clients on the old route. The
 * stable selector below reads the current config for every new connection, so enabling/disabling a
 * proxy and rotating Mysterium leases immediately affects existing OkHttp/Media3 clients as well.
 *
 * Mysterium leases use a loopback CONNECT bridge. That keeps short-lived upstream credentials out
 * of Media3/HttpURLConnection/OkHttp and lets every networking stack use the same unauthenticated
 * local proxy. Manual HTTP proxies continue to use the normal Java/OkHttp proxy authentication path.
 */
internal class JoynProxySettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): JoynProxyConfig {
        val host = prefs.getString(KEY_HOST, "").orEmpty()
        val transport = prefs.getString(KEY_TRANSPORT, null)
            ?.let { value -> runCatching { JoynProxyTransport.valueOf(value) }.getOrNull() }
            ?: JoynProxyTransport.HTTP
        return JoynProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            automatic = prefs.getBoolean(KEY_AUTOMATIC, host.isBlank()),
            transport = transport,
            host = host,
            port = prefs.getInt(KEY_PORT, 0),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            allTraffic = prefs.getBoolean(KEY_ALL_TRAFFIC, false),
            source = prefs.getString(KEY_SOURCE, "").orEmpty(),
            latencyMs = prefs.getLong(KEY_LATENCY_MS, -1L),
            lastVerifiedAtEpochMs = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L),
        )
    }

    /** Installs dynamic authentication only for a directly configured manual HTTP proxy. */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder
            .proxySelector(routingProxySelector)
            .proxyAuthenticator { _, response ->
                val config = current()
                if (!config.isUsable || config.isMysterium || config.username.isBlank()) {
                    null
                } else if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                        .build()
                }
            }
    }

    fun save(config: JoynProxyConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_AUTOMATIC, config.automatic)
            .putString(KEY_TRANSPORT, config.transport.name)
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_ALL_TRAFFIC, config.allTraffic)
            .putString(KEY_SOURCE, config.source)
            .putLong(KEY_LATENCY_MS, config.latencyMs)
            .putLong(KEY_LAST_VERIFIED_AT, config.lastVerifiedAtEpochMs)
            // Proxy/exit changes must not destroy account sessions. Joyn refreshes account access
            // tokens normally; the per-country refresh tokens remain valid across route changes.
            .apply()
        installProcessRouting(config)
    }

    fun disable() = save(current().copy(enabled = false))

    /**
     * Temporarily promotes the currently saved Mysterium route to all-traffic without persisting
     * the mode or invalidating the Joyn token. Used when a CDN/manifest rejects a direct stream.
     */
    fun enableTemporaryAllTraffic(): Boolean {
        val config = current()
        if (!config.isUsable || !config.isMysterium || config.allTraffic) return false
        installProcessRouting(config.copy(allTraffic = true))
        return true
    }

    /** Restores the persisted routing mode after a temporary player-only promotion. */
    fun restoreSavedRouting() {
        installProcessRouting(current())
    }

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ENABLED = "test_proxy_enabled"
        private const val KEY_AUTOMATIC = "test_proxy_automatic"
        private const val KEY_TRANSPORT = "test_proxy_transport"
        private const val KEY_HOST = "test_proxy_host"
        private const val KEY_PORT = "test_proxy_port"
        private const val KEY_USERNAME = "test_proxy_username"
        private const val KEY_PASSWORD = "test_proxy_password"
        private const val KEY_ALL_TRAFFIC = "test_proxy_all_traffic"
        private const val KEY_SOURCE = "test_proxy_source"
        private const val KEY_LATENCY_MS = "test_proxy_latency_ms"
        private const val KEY_LAST_VERIFIED_AT = "test_proxy_last_verified_at"

        // Capture this before installing our own selector. Making this lazy can accidentally capture
        // routingProxySelector itself and recurse forever when direct/fallback traffic is selected.
        private val originalProxySelector: ProxySelector? = ProxySelector.getDefault()
        private val originalAuthenticator: Authenticator? = null
        private val deadProxyAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)

        @Volatile
        private var connectFailureHandler: ((String) -> Unit)? = null

        private val controlHosts = setOf(
            "www.joyn.de",
            "www.joyn.at",
            "www.joyn.ch",
            "api.joyn.de",
            "auth.joyn.de",
            "auth.7pass.de",
            "entitlements-service-alb.prd.platform.s.joyn.de",
            "api.vod-prd.s.joyn.de",
        )

        /**
         * Stable selector intentionally kept for the lifetime of the app process.
         *
         * Existing OkHttp clients retain this object. Config changes only update the holder below;
         * the next connection therefore resolves the current Mysterium lease instead of a stale
         * local bridge address.
         */
        private val routingProxySelector = object : ProxySelector() {
            override fun select(uri: URI?): MutableList<Proxy> {
                if (uri == null) return mutableListOf(Proxy.NO_PROXY)
                val config = JoynProxySettingsHolder.currentConfig()
                val host = uri.host?.lowercase().orEmpty()
                if (config?.isUsable == true && shouldProxy(config, host)) {
                    val address = runCatching { proxyAddress(config) }.getOrElse { error ->
                        connectFailureHandler?.invoke(
                            buildString {
                                append("Proxy bridge")
                                error.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                            },
                        )
                        // Never silently fall back to the direct connection when proxy mode is on.
                        // A fast loopback refusal is safer than leaking the request outside the
                        // selected market and lets the automatic Mysterium failover react.
                        deadProxyAddress
                    }
                    return mutableListOf(Proxy(Proxy.Type.HTTP, address))
                }
                return fallbackSelect(uri)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                val config = JoynProxySettingsHolder.currentConfig()
                val host = uri?.host?.lowercase().orEmpty()
                if (config?.isUsable == true && shouldProxy(config, host)) {
                    val reason = buildString {
                        append(uri?.host ?: "Proxy")
                        ioe?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                    }
                    connectFailureHandler?.invoke(reason)
                } else {
                    runCatching { originalProxySelector?.connectFailed(uri, sa, ioe) }
                }
            }
        }

        fun install(context: Context) {
            installProcessRouting(JoynProxySettings(context).current())
        }

        fun setConnectFailureHandler(handler: ((String) -> Unit)?) {
            connectFailureHandler = handler
        }

        fun installDirectForTunnel() {
            JoynMysteriumProxyBridge.stopShared()
            JoynProxySettingsHolder.clear()
            // Keep our stable selector installed so OkHttp clients created before/after this call
            // behave identically. With an empty holder it simply delegates to the original route.
            ProxySelector.setDefault(routingProxySelector)
            Authenticator.setDefault(originalAuthenticator)
        }

        private fun installProcessRouting(config: JoynProxyConfig) {
            JoynProxySettingsHolder.update(config)
            ProxySelector.setDefault(routingProxySelector)

            if (!config.isUsable || !config.isMysterium) {
                JoynMysteriumProxyBridge.stopShared()
            }

            if (!config.isUsable || config.isMysterium) {
                // The local bridge is intentionally unauthenticated and injects the upstream lease
                // credentials itself. Do not expose those credentials to Java networking stacks.
                Authenticator.setDefault(originalAuthenticator)
            } else {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        if (requestorType != RequestorType.PROXY) return null
                        val current = JoynProxySettingsHolder.currentConfig()
                        return current
                            ?.takeIf { it.isUsable && !it.isMysterium && it.username.isNotBlank() }
                            ?.let { PasswordAuthentication(it.username, it.password.toCharArray()) }
                    }
                })
            }
        }

        private fun shouldProxy(config: JoynProxyConfig, host: String): Boolean =
            config.allTraffic || host in controlHosts

        private fun proxyAddress(config: JoynProxyConfig): InetSocketAddress =
            if (config.isMysterium) {
                JoynMysteriumProxyBridge.shared(config) { reason ->
                    connectFailureHandler?.invoke(reason)
                }
            } else {
                InetSocketAddress.createUnresolved(config.host.trim(), config.port)
            }

        private fun fallbackSelect(uri: URI): MutableList<Proxy> =
            runCatching { originalProxySelector?.select(uri)?.toMutableList() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: mutableListOf(Proxy.NO_PROXY)
    }
}

/** Small process-local snapshot used by the stable ProxySelector and java.net.Authenticator. */
private object JoynProxySettingsHolder {
    @Volatile
    private var config: JoynProxyConfig? = null

    fun update(value: JoynProxyConfig) {
        config = value
    }

    fun clear() {
        config = null
    }

    fun currentConfig(): JoynProxyConfig? = config
}
