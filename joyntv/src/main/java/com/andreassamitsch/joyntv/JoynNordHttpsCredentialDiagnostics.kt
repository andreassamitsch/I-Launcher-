package com.andreassamitsch.joyntv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

internal enum class JoynNordHttpsCredentialStatus {
    CONFIRMED,
    REJECTED,
    INCONCLUSIVE,
}

internal data class JoynNordHttpsCredentialDiagnosticReport(
    val status: JoynNordHttpsCredentialStatus,
    val attempted: Int,
    val summary: String,
)

/**
 * Fast credential gate for NordVPN's HTTPS proxy service on port 89.
 *
 * This deliberately runs before the expensive Joyn end-to-end scan. It only treats credentials as
 * rejected when multiple Nord-advertised proxy_ssl endpoints are technically reachable and all of
 * them explicitly answer HTTP 407. Transport/TLS failures are inconclusive and must never reject a
 * valid account. A single HTTP 200 CONNECT confirms that the supplied credentials are accepted by
 * the port-89 service.
 */
internal class JoynNordHttpsCredentialDiagnostics {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun run(
        country: JoynCountry,
        username: String,
        password: String,
    ): JoynNordHttpsCredentialDiagnosticReport {
        if (username.isBlank() || password.isBlank()) {
            return JoynNordHttpsCredentialDiagnosticReport(
                JoynNordHttpsCredentialStatus.REJECTED,
                0,
                "HTTPS/89-Credentials fehlen.",
            )
        }

        val hosts = loadProxySslHosts(country).take(MAX_TEST_HOSTS)
        if (hosts.isEmpty()) {
            return JoynNordHttpsCredentialDiagnosticReport(
                JoynNordHttpsCredentialStatus.INCONCLUSIVE,
                0,
                "HTTPS/89-Credential-Precheck nicht möglich: Nord lieferte keine proxy_ssl-Testserver.",
            )
        }

        var authRejected = 0
        var reachable = 0
        val details = mutableListOf<String>()

        hosts.forEach { host ->
            when (val result = testHost(host, username, password)) {
                is HttpsCredentialProbe.Confirmed -> {
                    return JoynNordHttpsCredentialDiagnosticReport(
                        JoynNordHttpsCredentialStatus.CONFIRMED,
                        reachable + 1,
                        "HTTPS/89-Credentials: OK · ${proxyHostname(result.host)} akzeptiert CONNECT/Auth.",
                    )
                }
                is HttpsCredentialProbe.Rejected -> {
                    reachable++
                    authRejected++
                    details += "${result.host}: ${result.detail}"
                }
                is HttpsCredentialProbe.Inconclusive -> {
                    details += "${result.host}: ${result.detail}"
                }
            }
        }

        if (reachable >= MIN_EXPLICIT_REJECTIONS && authRejected == reachable) {
            return JoynNordHttpsCredentialDiagnosticReport(
                JoynNordHttpsCredentialStatus.REJECTED,
                hosts.size,
                "HTTPS/89-Credentials abgelehnt: $authRejected erreichbare Nord-Proxys antworteten mit HTTP 407. " +
                    "Die große Proxy-Suche wird übersprungen. ${details.joinToString(" | ").take(360)}",
            )
        }

        return JoynNordHttpsCredentialDiagnosticReport(
            JoynNordHttpsCredentialStatus.INCONCLUSIVE,
            hosts.size,
            "HTTPS/89-Credential-Precheck nicht eindeutig; vollständige Suche wird fortgesetzt. " +
                details.joinToString(" | ").take(360),
        )
    }

    private fun proxyHostname(host: String): String {
        val normalized = host.trim().lowercase()
        return if (normalized.endsWith(".proxy.nordvpn.com")) {
            normalized
        } else {
            normalized.removeSuffix(".nordvpn.com") + ".proxy.nordvpn.com"
        }
    }

    private fun loadProxySslHosts(country: JoynCountry): List<String> {
        val url = "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("filters[country_id]", countryId(country).toString())
            .addQueryParameter("filters[servers_technologies][identifier]", "proxy_ssl")
            .build()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val array = JSONArray(response.body.string())
                buildList {
                    for (index in 0 until array.length()) {
                        val host = array.optJSONObject(index)?.optString("hostname")?.trim().orEmpty()
                        if (host.isNotBlank()) add(host)
                    }
                }.distinct()
            }
        }.getOrDefault(emptyList())
    }

    private fun testHost(
        host: String,
        username: String,
        password: String,
    ): HttpsCredentialProbe {
        return try {
            JoynNordProxyTls.connect(
                host = host,
                port = HTTPS_PROXY_PORT,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ).socket.use { tls ->
                val credentials = Base64.getEncoder().encodeToString(
                    "$username:$password".toByteArray(StandardCharsets.ISO_8859_1),
                )
                val request = buildString {
                    append("CONNECT www.cloudflare.com:443 HTTP/1.1\r\n")
                    append("Host: www.cloudflare.com:443\r\n")
                    append("Proxy-Authorization: Basic $credentials\r\n")
                    append("Proxy-Connection: Keep-Alive\r\n")
                    append("Connection: Keep-Alive\r\n\r\n")
                }
                tls.outputStream.write(request.toByteArray(StandardCharsets.ISO_8859_1))
                tls.outputStream.flush()

                val reader = BufferedReader(InputStreamReader(tls.inputStream, StandardCharsets.ISO_8859_1))
                val statusLine = reader.readLine().orEmpty()
                var proxyAuthenticate = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Proxy-Authenticate:", ignoreCase = true)) {
                        proxyAuthenticate = line.substringAfter(':').trim()
                    }
                }

                when {
                    statusLine.contains(" 200 ") -> HttpsCredentialProbe.Confirmed(host)
                    statusLine.contains(" 407 ") -> HttpsCredentialProbe.Rejected(
                        host,
                        buildString {
                            append("HTTP 407")
                            if (proxyAuthenticate.isNotBlank()) append(" · Proxy-Authenticate=$proxyAuthenticate")
                        },
                    )
                    else -> HttpsCredentialProbe.Inconclusive(
                        host,
                        statusLine.ifBlank { "keine HTTP-Antwort" },
                    )
                }
            }
        } catch (error: Throwable) {
            val text = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(140)
            HttpsCredentialProbe.Inconclusive(
                host,
                if (text.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $text",
            )
        }
    }

    private fun countryId(country: JoynCountry): Int = when (country) {
        JoynCountry.DE -> 81
        JoynCountry.AT -> 14
        JoynCountry.CH -> 209
    }

    private sealed interface HttpsCredentialProbe {
        data class Confirmed(val host: String) : HttpsCredentialProbe
        data class Rejected(val host: String, val detail: String) : HttpsCredentialProbe
        data class Inconclusive(val host: String, val detail: String) : HttpsCredentialProbe
    }

    private companion object {
        private const val HTTPS_PROXY_PORT = 89
        private const val MAX_TEST_HOSTS = 3
        private const val MIN_EXPLICIT_REJECTIONS = 2
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val READ_TIMEOUT_MS = 5_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
