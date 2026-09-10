package com.andreassamitsch.joyntv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS compatibility layer for NordVPN's legacy HTTPS proxy endpoints (normally port 89).
 *
 * Android's HTTPS endpoint identification rejects some Nord proxy certificates because their
 * subjectAltName set does not match the individual de1234.nordvpn.com style hostname. Current
 * third-party Nord proxy clients commonly work around this with curl --proxy-insecure, but doing
 * that here would also disable certificate-chain validation for the proxy hop.
 *
 * Instead we keep the platform/default trust manager and therefore normal CA-chain validation,
 * while performing a narrowly scoped Nord hostname check ourselves after the TLS handshake.
 * A connection is accepted only for a *.nordvpn.com host and only when the trusted leaf
 * certificate contains either a matching DNS SAN or a matching legacy CN such as
 * *.nordvpn.com. TLS to Joyn and every other destination remains untouched.
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
        val normalizedHost = host.trim().lowercase()
        require(normalizedHost.endsWith(NORD_DOMAIN_SUFFIX) && normalizedHost.length > NORD_DOMAIN_SUFFIX.length) {
            "Nord proxy host outside allowed domain: $host"
        }

        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            raw.tcpNoDelay = true

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
            tls.useClientMode = true
            tls.soTimeout = readTimeoutMs
            tls.tcpNoDelay = true

            // Raw SSLSocket handshakes validate the certificate chain using the platform trust
            // manager. We deliberately do not enable JSSE's HTTPS endpoint identification here,
            // because that is exactly what rejects Nord's legacy proxy certificate. Identity is
            // checked immediately below with a Nord-only policy.
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
            val sanMatch = dnsSans.any { dnsMatches(it, normalizedHost) }
            val cnMatch = commonName?.let { dnsMatches(it, normalizedHost) } == true

            if (!sanMatch && !cnMatch) {
                val summary = certificateSummary(certificate, dnsSans, commonName)
                tls.close()
                throw SSLHandshakeException(
                    "Trusted certificate is not a Nord identity for $normalizedHost · $summary",
                )
            }

            val mode = when {
                sanMatch -> "SAN"
                else -> "legacy-CN"
            }
            return Connection(
                socket = tls,
                certificateSummary = "$mode · ${certificateSummary(certificate, dnsSans, commonName)}",
            )
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
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

        val suffix = pattern.substring(1) // e.g. .nordvpn.com
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
}
