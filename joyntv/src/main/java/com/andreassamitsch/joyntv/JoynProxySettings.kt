package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.net.Authenticator
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
 * Process-wide routing for the active Mysterium residential HTTP proxy.
 *
 * By default only Joyn's control-plane hosts use the proxy. Full traffic can be enabled per country
 * profile. Proxy credentials are read dynamically for every authentication challenge so an active
 * OkHttp client automatically picks up a newly rotated Mysterium lease.
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

    /** Installs dynamic HTTP-proxy authentication on an OkHttp builder. */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder.proxyAuthenticator { _, response ->
            val config = current()
            if (!config.isUsable || config.username.isBlank()) {
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
            // A Joyn auth token can be tied to the previous source IP/market.
            .remove("auth_token")
            .apply()
        installProcessRouting(config)
    }

    fun disable() = save(current().copy(enabled = false))

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

        private val originalProxySelector: ProxySelector? by lazy { ProxySelector.getDefault() }
        private val originalAuthenticator: Authenticator? = null

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

        fun install(context: Context) {
            installProcessRouting(JoynProxySettings(context).current())
        }

        fun setConnectFailureHandler(handler: ((String) -> Unit)?) {
            connectFailureHandler = handler
        }

        fun installDirectForTunnel() {
            ProxySelector.setDefault(originalProxySelector)
            Authenticator.setDefault(originalAuthenticator)
        }

        private fun installProcessRouting(config: JoynProxyConfig) {
            if (!config.isUsable) {
                installDirectForTunnel()
                return
            }

            val proxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(config.host.trim(), config.port),
            )
            val fallback = originalProxySelector
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    if (uri == null) return mutableListOf(Proxy.NO_PROXY)
                    val host = uri.host?.lowercase().orEmpty()
                    val shouldProxy = config.allTraffic || host in controlHosts
                    if (shouldProxy) return mutableListOf(proxy)
                    return runCatching { fallback?.select(uri)?.toMutableList() }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: mutableListOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    val reason = buildString {
                        append(uri?.host ?: "Proxy")
                        ioe?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                    }
                    connectFailureHandler?.invoke(reason)
                }
            })

            // HttpURLConnection/Media3 may use java.net.Authenticator. OkHttp receives the same
            // current credentials through configure(), so both stacks survive lease rotation.
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) return null
                    val current = JoynProxySettingsHolder.currentConfig()
                    return current
                        ?.takeIf { it.isUsable && it.username.isNotBlank() }
                        ?.let { PasswordAuthentication(it.username, it.password.toCharArray()) }
                }
            })
            JoynProxySettingsHolder.update(config)
        }
    }
}

/** Small process-local snapshot used by java.net.Authenticator without retaining an Activity. */
private object JoynProxySettingsHolder {
    @Volatile
    private var config: JoynProxyConfig? = null

    fun update(value: JoynProxyConfig) {
        config = value
    }

    fun currentConfig(): JoynProxyConfig? = config
}
