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
 * Tests the supplied credentials against Nord's documented SOCKS5 service. This is deliberately
 * only an additional protocol-specific diagnostic. A SOCKS5 failure must never be interpreted as
 * proof that the same credentials are invalid for Nord's HTTPS/89 proxy service.
 */
internal class JoynNordCredentialDiagnostics {
    fun run(username: String, password: String): JoynNordCredentialDiagnosticReport {
        if (username.isBlank() || password.isBlank()) {
            return JoynNordCredentialDiagnosticReport("SOCKS5-Credential-Check: Zugangsdaten fehlen")
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
                            "SOCKS5-Credential-Check: OK · $host:$SOCKS_PORT akzeptiert Login. " +
                                "Dieser Zusatztest bewertet nur SOCKS5, nicht HTTPS/89.",
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
                    "SOCKS5-Credential-Check: fehlgeschlagen · das ist KEIN Beweis für falsche HTTPS/89-Credentials. " +
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
