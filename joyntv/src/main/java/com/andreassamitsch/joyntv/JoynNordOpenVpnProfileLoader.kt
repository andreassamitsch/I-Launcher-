package com.andreassamitsch.joyntv

import android.content.Context
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

internal enum class JoynNordOpenVpnTransport {
    UDP,
    TCP,
}

internal data class JoynNordOpenVpnServer(
    val hostname: String,
    val station: String,
    val load: Int,
    val recommended: Boolean = false,
    val supportsUdp: Boolean = false,
    val supportsTcp: Boolean = false,
)

internal enum class JoynNordOpenVpnTlsMode {
    AUTH,
    CRYPT,
}

internal data class JoynNordOpenVpnProfile(
    val hostname: String,
    val remoteHost: String,
    val remotePort: Int,
    val protocol: String,
    val caPath: String,
    val tlsKeyPath: String?,
    val tlsMode: JoynNordOpenVpnTlsMode?,
    val keyDirection: String?,
    val verifyX509Args: List<String>,
    val auth: String,
    val cipher: String,
    val dataCiphers: String,
    val tlsCipher: String?,
    val tunMtu: Int,
    val mssFix: Int?,
)

/** Loads current normal NordVPN OpenVPN servers and their official UDP/TCP profiles. */
internal class JoynNordOpenVpnProfileLoader(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun loadServers(country: JoynCountry): List<JoynNordOpenVpnServer> {
        data class Source(
            val recommended: Boolean,
            val transport: JoynNordOpenVpnTransport,
            val url: String,
        )

        fun endpoint(
            base: String,
            limit: Int,
            technology: String,
        ): String = base.toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("filters[country_id]", countryId(country).toString())
            .addQueryParameter("filters[servers_technologies][identifier]", technology)
            .build()
            .toString()

        val sources = listOf(
            Source(
                recommended = true,
                transport = JoynNordOpenVpnTransport.UDP,
                url = endpoint(
                    "https://api.nordvpn.com/v1/servers/recommendations",
                    100,
                    "openvpn_udp",
                ),
            ),
            Source(
                recommended = true,
                transport = JoynNordOpenVpnTransport.TCP,
                url = endpoint(
                    "https://api.nordvpn.com/v1/servers/recommendations",
                    100,
                    "openvpn_tcp",
                ),
            ),
            Source(
                recommended = false,
                transport = JoynNordOpenVpnTransport.UDP,
                url = endpoint(
                    "https://api.nordvpn.com/v1/servers",
                    500,
                    "openvpn_udp",
                ),
            ),
            Source(
                recommended = false,
                transport = JoynNordOpenVpnTransport.TCP,
                url = endpoint(
                    "https://api.nordvpn.com/v1/servers",
                    500,
                    "openvpn_tcp",
                ),
            ),
        )

        val merged = linkedMapOf<String, JoynNordOpenVpnServer>()
        sources.forEach { source ->
            val body = execute(source.url) ?: return@forEach
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hostname = item.optString("hostname").trim().lowercase()
                if (hostname.isBlank() || !hostname.startsWith(country.name.lowercase())) continue
                val status = item.optString("status")
                if (status.isNotBlank() && !status.equals("online", ignoreCase = true)) continue

                val station = item.optString("station").trim()
                val old = merged[hostname]
                val load = item.optInt("load", 100)
                val supportsUdp = source.transport == JoynNordOpenVpnTransport.UDP
                val supportsTcp = source.transport == JoynNordOpenVpnTransport.TCP

                merged[hostname] = if (old == null) {
                    JoynNordOpenVpnServer(
                        hostname = hostname,
                        station = station,
                        load = load,
                        recommended = source.recommended,
                        supportsUdp = supportsUdp,
                        supportsTcp = supportsTcp,
                    )
                } else {
                    old.copy(
                        station = old.station.ifBlank { station },
                        load = minOf(old.load, load),
                        recommended = old.recommended || source.recommended,
                        supportsUdp = old.supportsUdp || supportsUdp,
                        supportsTcp = old.supportsTcp || supportsTcp,
                    )
                }
            }
        }

        // Nord recommendations are the freshest signal. Inside that group prefer low-load servers.
        return merged.values.sortedWith(
            compareByDescending<JoynNordOpenVpnServer> { it.recommended }
                .thenBy { it.load }
                .thenBy { it.hostname },
        )
    }

    fun loadProfile(
        server: JoynNordOpenVpnServer,
        transport: JoynNordOpenVpnTransport,
    ): JoynNordOpenVpnProfile {
        val directory: String
        val suffix: String
        val defaultProtocol: String
        when (transport) {
            JoynNordOpenVpnTransport.UDP -> {
                directory = "ovpn_udp"
                suffix = "udp"
                defaultProtocol = "udp"
            }
            JoynNordOpenVpnTransport.TCP -> {
                directory = "ovpn_tcp"
                suffix = "tcp"
                defaultProtocol = "tcp-client"
            }
        }

        val url = "https://downloads.nordcdn.com/configs/files/$directory/servers/${server.hostname}.$suffix.ovpn"
        val text = execute(url)
            ?: error("Nord OpenVPN-$suffix-Profil für ${server.hostname} konnte nicht geladen werden")

        val remoteLine = directive(text, "remote")
            ?: error("Nord-Profil ${server.hostname}: remote fehlt")
        val remoteParts = remoteLine.split(Regex("\\s+")).filter(String::isNotBlank)
        val remoteHost = remoteParts.getOrNull(0)
            ?: error("Nord-Profil ${server.hostname}: remote host fehlt")
        val remotePort = remoteParts.getOrNull(1)?.toIntOrNull()
            ?: error("Nord-Profil ${server.hostname}: remote port fehlt")

        val protocol = directive(text, "proto")?.substringBefore(' ')?.trim().orEmpty()
            .ifBlank { defaultProtocol }
        val caPem = inlineBlock(text, "ca")
            ?: error("Nord-Profil ${server.hostname}: CA-Zertifikat fehlt")

        val tlsAuth = inlineBlock(text, "tls-auth")
        val tlsCrypt = inlineBlock(text, "tls-crypt")
        val tlsMode = when {
            tlsAuth != null -> JoynNordOpenVpnTlsMode.AUTH
            tlsCrypt != null -> JoynNordOpenVpnTlsMode.CRYPT
            else -> null
        }
        val tlsKey = tlsAuth ?: tlsCrypt
        val keyDirection = directive(text, "key-direction")?.substringBefore(' ')?.trim()

        val safeHost = server.hostname.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val profileDir = File(appContext.filesDir, "nord_openvpn").apply { mkdirs() }
        val caFile = File(profileDir, "$safeHost.$suffix.ca.crt").apply { writeText(caPem) }
        val tlsFile = tlsKey?.let { key ->
            File(profileDir, "$safeHost.$suffix.tls.key").apply { writeText(key) }
        }

        val verifyX509Args = directive(text, "verify-x509-name")
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
            .orEmpty()

        val cipher = directive(text, "cipher")?.substringBefore(' ')?.trim().orEmpty()
            .ifBlank { "AES-256-CBC" }
        val auth = directive(text, "auth")?.substringBefore(' ')?.trim().orEmpty()
            .ifBlank { "SHA512" }
        val dataCiphers = directive(text, "data-ciphers")?.trim().orEmpty()
            .ifBlank { "AES-256-GCM:AES-128-GCM:$cipher" }
        val tlsCipher = directive(text, "tls-cipher")?.trim()?.takeIf(String::isNotBlank)
        val tunMtu = directive(text, "tun-mtu")?.substringBefore(' ')?.toIntOrNull() ?: 1500
        val mssFix = directive(text, "mssfix")?.substringBefore(' ')?.toIntOrNull()

        return JoynNordOpenVpnProfile(
            hostname = server.hostname,
            remoteHost = remoteHost,
            remotePort = remotePort,
            protocol = normalizeProtocol(protocol),
            caPath = caFile.absolutePath,
            tlsKeyPath = tlsFile?.absolutePath,
            tlsMode = tlsMode,
            keyDirection = keyDirection,
            verifyX509Args = verifyX509Args,
            auth = auth,
            cipher = cipher,
            dataCiphers = dataCiphers,
            tlsCipher = tlsCipher,
            tunMtu = tunMtu,
            mssFix = mssFix,
        )
    }

    private fun execute(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body.string()
        }
    }.getOrNull()

    private fun directive(text: String, name: String): String? =
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.isNotBlank() && !line.startsWith('#') && !line.startsWith(';') &&
                    (line == name || line.startsWith("$name ") || line.startsWith("$name\t"))
            }
            ?.removePrefix(name)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun inlineBlock(text: String, tag: String): String? {
        val regex = Regex("(?s)<$tag>\\s*(.*?)\\s*</$tag>", RegexOption.IGNORE_CASE)
        val body = regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        return "$body\n"
    }

    private fun normalizeProtocol(value: String): String = when (value.lowercase()) {
        "tcp", "tcp-client", "tcp4-client" -> "tcp-client"
        "udp", "udp4" -> "udp"
        else -> value.lowercase()
    }

    private fun countryId(country: JoynCountry): Int = when (country) {
        JoynCountry.DE -> 81
        JoynCountry.AT -> 14
        JoynCountry.CH -> 209
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
