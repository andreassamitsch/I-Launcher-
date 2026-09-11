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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Verifies that a newly switched app-scoped Mysterium tunnel is actually usable before Joyn starts
 * an entitlement/playback request.
 *
 * The probe is intentionally short. Normal AT/DE/CH switches now reuse prepared country-specific
 * WireGuard connections; if that cached path is stale the repository refreshes exactly that target
 * once instead of making the user wait through the old 12-second readiness timeout.
 */
internal object JoynMysteriumTunnelProbe {
    private val ipv4OnlyDns = Dns { hostname ->
        val addresses = InetAddress.getAllByName(hostname).filterIsInstance<Inet4Address>()
        if (addresses.isEmpty()) throw UnknownHostException("No IPv4 address for $hostname")
        addresses
    }

    private val client = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

            while (System.currentTimeMillis() < deadline) {
                if (!JoynMysteriumWireGuard.isConnected(appContext)) {
                    lastDetail = "WireGuard meldet noch nicht UP"
                } else {
                    val trace = resolveExit()
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

            error("Mysterium ${country.name} Tunnel wurde nicht rechtzeitig bereit: $lastDetail")
        }
    }

    private fun resolveExit(): ExitTrace? = runCatching {
        client.newCall(
            Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", USER_AGENT)
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

    private const val DEFAULT_TIMEOUT_MS = 5_000L
    private const val POLL_DELAY_MS = 200L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
