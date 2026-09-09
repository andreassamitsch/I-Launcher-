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
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val allTraffic: Boolean = false,
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

    fun current(): JoynProxyConfig = JoynProxyConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        port = prefs.getInt(KEY_PORT, 0),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        allTraffic = prefs.getBoolean(KEY_ALL_TRAFFIC, false),
    )

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
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_ALL_TRAFFIC, config.allTraffic)
            // A Joyn auth token can be tied to the previous source IP/market.
            .remove("auth_token")
            .apply()
        installProcessRouting(config)
    }

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ENABLED = "test_proxy_enabled"
        private const val KEY_HOST = "test_proxy_host"
        private const val KEY_PORT = "test_proxy_port"
        private const val KEY_USERNAME = "test_proxy_username"
        private const val KEY_PASSWORD = "test_proxy_password"
        private const val KEY_ALL_TRAFFIC = "test_proxy_all_traffic"

        private val originalProxySelector: ProxySelector? by lazy { ProxySelector.getDefault() }

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
            }
        }
    }
}
