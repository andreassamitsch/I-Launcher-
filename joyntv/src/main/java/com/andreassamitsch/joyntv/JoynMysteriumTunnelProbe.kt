package com.andreassamitsch.joyntv

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Verifies that a newly switched app-scoped Mysterium tunnel is actually usable before Joyn starts
 * an entitlement/playback request.
 *
 * A probe must never reuse an HTTP socket from the previously active country. Android keeps an
 * established socket on the network it was created on even after the VpnService is reconfigured.
 * Reusing that socket made a correct DE -> CH switch look like the old DE exit until the readiness
 * timeout expired, which in turn triggered the slow server-side refresh path on every switch.
 */
internal object JoynMysteriumTunnelProbe {
    private val ipv4OnlyDns = Dns { hostname ->
        val addresses = InetAddress.getAllByName(hostname).filterIsInstance<Inet4Address>()
        if (addresses.isEmpty()) throw UnknownHostException("No IPv4 address for $hostname")
        addresses
    }

    suspend fun awaitReady(
        context: Context,
        country: JoynCountry,
        expectedExitIp: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000L)
            var lastDetail = "noch kein IPv4-Exit erreichbar"

            // A brand-new pool is intentional. A singleton OkHttp client can retain a TLS/HTTP2
            // connection that was opened through the previous country tunnel and therefore report
            // the previous public IP after the WireGuard handover.
            val probeClient = newProbeClient()
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (!JoynMysteriumWireGuard.isConnected(appContext)) {
                        lastDetail = "WireGuard meldet noch nicht UP"
                    } else {
                        val trace = resolveExit(probeClient)
                        if (trace != null) {
                            val countryOk = trace.country.equals(country.name, ignoreCase = true)
                            val ipOk = expectedExitIp.isBlank() || trace.ip == expectedExitIp
                            if (countryOk && ipOk) return@runCatching Unit

                            lastDetail = when {
                                !countryOk -> "Exit ${trace.ip} liegt in ${trace.country} statt ${country.name}"
                                else -> "Exit ${trace.ip} statt gespeicherter IP $expectedExitIp"
                            }
                        }
                    }
                    delay(POLL_DELAY_MS)
                }
            } finally {
                probeClient.connectionPool.evictAll()
                probeClient.dispatcher.cancelAll()
            }

            error("Mysterium ${country.name} Tunnel wurde nicht rechtzeitig bereit: $lastDetail")
        }
    }

    private fun newProbeClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .callTimeout(1600, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun resolveExit(client: OkHttpClient): ExitTrace? = runCatching {
        client.newCall(
            Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", USER_AGENT)
                .header("Connection", "close")
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
            if (ip.isBlank() || country.isBlank() || ip.contains(':')) null else ExitTrace(ip, country)
        }
    }.getOrNull()

    private data class ExitTrace(val ip: String, val country: String)

    private const val DEFAULT_TIMEOUT_MS = 3_000L
    private const val POLL_DELAY_MS = 120L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
