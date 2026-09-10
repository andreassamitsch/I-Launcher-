package com.andreassamitsch.joyntv

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket

internal data class JoynNordCredentialDiagnosticReport(
    val summary: String,
)

/**
 * Verifies the stored NordVPN service credentials against Nord's currently documented SOCKS5
 * service. This diagnostic is intentionally independent of the requested Joyn country: Nord only
 * documents SOCKS5 exits in NL/SE/US at the moment, but a successful SOCKS5 CONNECT still proves
 * that the service username/password pair itself is accepted by Nord.
 */
internal class JoynNordCredentialDiagnostics {
    fun run(username: String, password: String): JoynNordCredentialDiagnosticReport {
        if (username.isBlank() || password.isBlank()) {
            return JoynNordCredentialDiagnosticReport("Service-Credentials: fehlen")
        }

        return synchronized(AUTH_LOCK) {
            val failures = mutableListOf<String>()
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) {
                        PasswordAuthentication(username, password.toCharArray())
                    } else {
                        null
                    }
            })

            try {
                for (host in OFFICIAL_SOCKS_HOSTS) {
                    val result = runCatching {
                        val proxy = Proxy(
                            Proxy.Type.SOCKS,
                            InetSocketAddress.createUnresolved(host, SOCKS_PORT),
                        )
                        Socket(proxy).use { socket ->
                            socket.connect(
                                InetSocketAddress(TEST_TARGET_HOST, TEST_TARGET_PORT),
                                CONNECT_TIMEOUT_MS,
                            )
                        }
                    }

                    if (result.isSuccess) {
                        return@synchronized JoynNordCredentialDiagnosticReport(
                            "Service-Credentials: OK · offizieller Nord SOCKS5 $host:$SOCKS_PORT akzeptiert Login",
                        )
                    }

                    val error = result.exceptionOrNull()
                    val detail = buildString {
                        append(error?.javaClass?.simpleName ?: "Fehler")
                        val message = error?.message.orEmpty().replace('\n', ' ').replace('\r', ' ').trim()
                        if (message.isNotBlank()) append(": ${message.take(120)}")
                    }
                    failures += "$host: $detail"
                }

                JoynNordCredentialDiagnosticReport(
                    "Service-Credentials: NICHT bestätigt · offizielle Nord SOCKS5-Tests fehlgeschlagen · " +
                        failures.joinToString(" | ").take(420),
                )
            } finally {
                Authenticator.setDefault(null)
            }
        }
    }

    private companion object {
        private val AUTH_LOCK = Any()
        private val OFFICIAL_SOCKS_HOSTS = listOf(
            "nl.socks.nordhold.net",
            "se.socks.nordhold.net",
            "us.socks.nordhold.net",
        )
        private const val SOCKS_PORT = 1080
        private const val TEST_TARGET_HOST = "1.1.1.1"
        private const val TEST_TARGET_PORT = 443
        private const val CONNECT_TIMEOUT_MS = 6_000
    }
}
