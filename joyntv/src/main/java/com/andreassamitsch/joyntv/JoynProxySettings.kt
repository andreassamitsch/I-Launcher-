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

internal data class JoynProxyConfig(
    val enabled: Boolean = false,
    val automatic: Boolean = false,
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
}

/**
 * Optional test proxy for validating DE/AT/CH markets from one physical location.
 *
 * Default mode is deliberately selective: only Joyn control-plane hosts (web bootstrap,
 * auth/7Pass, GraphQL, entitlement and playlist resolution) use the proxy. Media CDN,
 * DRM/license and update traffic remain on the normal connection. Full proxy mode is
 * available only as an explicit test fallback.
 */
internal class JoynProxySettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): JoynProxyConfig {
        val host = prefs.getString(KEY_HOST, "").orEmpty()
        return JoynProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            automatic = prefs.getBoolean(KEY_AUTOMATIC, host.isBlank()),
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

    /** Adds HTTP proxy authentication to OkHttp. Proxy selection itself is process-wide so
     * Media3 can optionally participate when full-proxy mode is selected. */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val config = current()
        if (!config.isUsable || config.username.isBlank()) return builder
        val credential = Credentials.basic(config.username, config.password)
        return builder.proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
    }

    fun save(config: JoynProxyConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_AUTOMATIC, config.automatic)
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

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ENABLED = "test_proxy_enabled"
        private const val KEY_AUTOMATIC = "test_proxy_automatic"
        private const val KEY_HOST = "test_proxy_host"
        private const val KEY_PORT = "test_proxy_port"
        private const val KEY_USERNAME = "test_proxy_username"
        private const val KEY_PASSWORD = "test_proxy_password"
        private const val KEY_ALL_TRAFFIC = "test_proxy_all_traffic"
        private const val KEY_SOURCE = "test_proxy_source"
        private const val KEY_LATENCY_MS = "test_proxy_latency_ms"
        private const val KEY_LAST_VERIFIED_AT = "test_proxy_last_verified_at"

        private val originalProxySelector: ProxySelector? by lazy { ProxySelector.getDefault() }
        private val originalAuthenticator: Authenticator? by lazy { Authenticator.getDefault() }

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

        private fun installProcessRouting(config: JoynProxyConfig) {
            if (!config.isUsable) {
                ProxySelector.setDefault(originalProxySelector)
                Authenticator.setDefault(originalAuthenticator)
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

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })

            // HttpURLConnection/Media3 can use the JDK authenticator in full-proxy mode.
            if (config.username.isNotBlank()) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        return if (requestorType == RequestorType.PROXY) {
                            PasswordAuthentication(config.username, config.password.toCharArray())
                        } else null
                    }
                })
            } else {
                Authenticator.setDefault(originalAuthenticator)
            }
        }
    }
}
