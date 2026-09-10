package com.andreassamitsch.joyntv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS transport for NordVPN's HTTPS proxy service on port 89.
 *
 * Nord's server directory returns ordinary VPN hostnames such as de1544.nordvpn.com, while the
 * HTTPS proxy service uses the corresponding proxy hostname de1544.proxy.nordvpn.com. The proxy
 * certificate observed on-device is issued for *.proxy.nordvpn.com, which matches that service
 * hostname. We therefore derive and dial the proxy hostname instead of weakening TLS verification
 * for the ordinary VPN hostname.
 *
 * The platform/default trust manager still validates the certificate chain. We additionally check
 * the trusted leaf certificate against the derived *.proxy.nordvpn.com identity because Android
 * devices can differ in how raw SSLSocket hostname verification treats legacy CN-only certificates.
 * TLS from the CONNECT tunnel to Joyn remains completely independent and unchanged.
 */
internal object JoynNordProxyTls {
    internal data class Connection(
        val socket: SSLSocket,
        val certificateSummary: String,
    )

    fun connect(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Connection {
        val directoryHost = host.trim().lowercase()
        require(directoryHost.endsWith(NORD_DOMAIN_SUFFIX) && directoryHost.length > NORD_DOMAIN_SUFFIX.length) {
            "Nord proxy host outside allowed domain: $host"
        }

        val proxyHost = toProxyHost(directoryHost)
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(proxyHost, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            raw.tcpNoDelay = true

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, proxyHost, port, true) as SSLSocket
            tls.useClientMode = true
            tls.soTimeout = readTimeoutMs
            tls.tcpNoDelay = true

            // Keep normal CA-chain validation. Identity is checked explicitly immediately after the
            // handshake so CN-only Nord proxy certificates work consistently across Android levels.
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = null
            }
            tls.startHandshake()

            val certificate = tls.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: run {
                    tls.close()
                    throw SSLHandshakeException("Nord proxy returned no X509 certificate")
                }
            certificate.checkValidity()

            val dnsSans = readDnsSans(certificate)
            val commonName = readCommonName(certificate)
            val sanMatch = dnsSans.any { dnsMatches(it, proxyHost) }
            val cnMatch = commonName?.let { dnsMatches(it, proxyHost) } == true

            if (!sanMatch && !cnMatch) {
                val summary = certificateSummary(certificate, dnsSans, commonName)
                tls.close()
                throw SSLHandshakeException(
                    "Trusted certificate does not match Nord proxy endpoint $proxyHost · $summary",
                )
            }

            val mode = if (sanMatch) "SAN" else "CN"
            return Connection(
                socket = tls,
                certificateSummary = "$directoryHost → $proxyHost · $mode · ${certificateSummary(certificate, dnsSans, commonName)}",
            )
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun toProxyHost(host: String): String {
        if (host.endsWith(PROXY_DOMAIN_SUFFIX)) return host
        val serverLabel = host.removeSuffix(NORD_DOMAIN_SUFFIX)
        require(serverLabel.isNotBlank() && !serverLabel.contains('.')) {
            "Unsupported Nord server hostname for proxy mapping: $host"
        }
        return "$serverLabel.proxy.nordvpn.com"
    }

    private fun readDnsSans(certificate: X509Certificate): List<String> = runCatching {
        certificate.subjectAlternativeNames.orEmpty()
            .mapNotNull { entry ->
                if (entry.size >= 2 && (entry[0] as? Int) == DNS_SAN_TYPE) {
                    (entry[1] as? String)?.trim()?.lowercase()
                } else {
                    null
                }
            }
            .filter(String::isNotBlank)
    }.getOrDefault(emptyList())

    private fun readCommonName(certificate: X509Certificate): String? {
        val principal = certificate.subjectX500Principal.name
        return principal.split(',')
            .map(String::trim)
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
    }

    private fun dnsMatches(patternRaw: String, hostRaw: String): Boolean {
        val pattern = patternRaw.trim().lowercase()
        val host = hostRaw.trim().lowercase()
        if (pattern == host) return true
        if (!pattern.startsWith("*.")) return false

        val suffix = pattern.substring(1)
        if (!host.endsWith(suffix)) return false
        val firstLabel = host.removeSuffix(suffix)
        return firstLabel.isNotBlank() && !firstLabel.contains('.')
    }

    private fun certificateSummary(
        certificate: X509Certificate,
        dnsSans: List<String>,
        commonName: String?,
    ): String {
        val cn = commonName ?: "-"
        val san = if (dnsSans.isEmpty()) "-" else dnsSans.take(4).joinToString(",")
        return "CN=$cn · SAN=$san · issuer=${certificate.issuerX500Principal.name.take(90)}"
    }

    private const val DNS_SAN_TYPE = 2
    private const val NORD_DOMAIN_SUFFIX = ".nordvpn.com"
    private const val PROXY_DOMAIN_SUFFIX = ".proxy.nordvpn.com"
}
