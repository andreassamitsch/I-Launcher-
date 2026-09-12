package com.andreassamitsch.joyntv

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local unauthenticated CONNECT bridge for Mysterium's short-lived authenticated proxies.
 *
 * Mysterium's consumer connect-proxy endpoint currently returns superproxy hosts on port 8080.
 * PC measurements on 2026-09-12 found that the returned EU superproxy port 8080 refuses TCP,
 * while the same hosts accept authenticated CONNECT over verified TLS on port 443. Prefer that
 * measured endpoint for this specific legacy API response, retaining the original port as fallback.
 * Other proxy hosts/ports retain their original transport selection.
 *
 * Joyn clients in the app can therefore keep using Android's process ProxySelector without each
 * networking stack knowing Mysterium credentials. The bridge listens only on loopback, injects the
 * upstream Proxy-Authorization header and relays the established tunnel byte-for-byte.
 */
internal class JoynMysteriumProxyBridge(
    private val remoteHost: String,
    private val remotePort: Int,
    private val username: String,
    private val password: String,
    private val onFailure: (String) -> Unit,
) : Closeable {
    private val server = ServerSocket()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "joyn-mysterium-proxy-worker").apply { isDaemon = true }
    }
    @Volatile private var running = true

    val localAddress: InetSocketAddress

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        localAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
        Thread(::acceptLoop, "joyn-mysterium-proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                if (!running) return
                continue
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        var tunnelEstablished = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val requestHeader = readHeader(client) ?: return
            if (!requestHeader.startsWith("CONNECT ", ignoreCase = true)) {
                writeLocalError(client, 405, "CONNECT required")
                return
            }

            val connection = connectUpstreamProxy(requestHeader)
            remote = connection.socket

            client.outputStream.write(connection.responseHeader.toByteArray(Charsets.ISO_8859_1))
            client.outputStream.flush()

            // From this point on both ends are carrying the caller's encrypted HTTPS stream.
            // A player cancelling a request, changing channel or closing an idle keep-alive tunnel
            // is normal and must not rotate the residential IP. Real upstream failures will be
            // detected when the next CONNECT cannot be established.
            tunnelEstablished = true
            client.soTimeout = 0
            remote.soTimeout = 0
            val upstreamSocket = remote
            val upstream = workers.submit {
                try {
                    client.getInputStream().copyTo(upstreamSocket.getOutputStream(), 16 * 1024)
                    runCatching { upstreamSocket.shutdownOutput() }
                } catch (_: Throwable) {
                    // Normal when the downstream request is cancelled/closed.
                }
            }
            try {
                upstreamSocket.getInputStream().copyTo(client.getOutputStream(), 16 * 1024)
            } finally {
                upstream.cancel(true)
            }
        } catch (error: Throwable) {
            if (!tunnelEstablished) {
                val reason = buildString {
                    append(error.javaClass.simpleName)
                    error.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                }
                onFailure(reason)
                runCatching { writeLocalError(client, 502, "Mysterium proxy unavailable") }
            }
            // Never inject a plaintext HTTP 502 after CONNECT 200. At that point the socket carries
            // an encrypted TLS tunnel and writing an HTTP response would only corrupt the stream.
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
        }
    }

    /**
     * Opens the upstream proxy and performs CONNECT + Basic authentication.
     *
     * The known EU superproxy API response uses TLS:443 first (see PC test report). Other TLS
     * proxy ports are tried TLS-first. If the first transport is reset before an
     * HTTP response is received, a fresh connection is opened with the other transport. A real HTTP
     * rejection such as 407 is not retried using another transport because the protocol was already
     * identified correctly.
     */
    private fun connectUpstreamProxy(requestHeader: String): ProxyConnection {
        val endpoints = upstreamEndpoints(remoteHost, remotePort)
        val failures = mutableListOf<String>()

        for (endpoint in endpoints) {
            val transport = endpoint.transport
            var socket: Socket? = null
            try {
                socket = when (transport) {
                    UpstreamTransport.PLAIN_HTTP -> connectPlainProxy(endpoint.port)
                    UpstreamTransport.TLS -> connectTlsProxy(endpoint.port)
                }
                socket.outputStream.write(withProxyAuthorization(requestHeader).toByteArray(Charsets.ISO_8859_1))
                socket.outputStream.flush()

                val responseHeader = readHeader(socket)
                    ?: throw IOException("${transport.label} proxy sent no HTTP response")
                val statusLine = responseHeader.lineSequence().firstOrNull().orEmpty()
                val successful = statusLine.startsWith("HTTP/1.1 200") ||
                    statusLine.startsWith("HTTP/1.0 200")
                if (!successful) {
                    throw ProxyRejectedException("${transport.label} CONNECT rejected: $statusLine")
                }
                return ProxyConnection(socket, responseHeader, transport)
            } catch (rejected: ProxyRejectedException) {
                runCatching { socket?.close() }
                // Receiving a valid HTTP status proves that this is the correct upstream protocol.
                throw rejected
            } catch (error: Throwable) {
                runCatching { socket?.close() }
                failures += buildString {
                    append(transport.label)
                    append(":${endpoint.port}")
                    append(": ")
                    append(error.javaClass.simpleName)
                    error.message?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                }
            }
        }

        throw IOException(
            "Mysterium upstream ${remoteHost}:${remotePort} failed (${failures.joinToString(" | ")})",
        )
    }

    private fun connectPlainProxy(port: Int): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(remoteHost, port), HEADER_TIMEOUT_MS)
        soTimeout = HEADER_TIMEOUT_MS
    }

    private fun connectTlsProxy(port: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(remoteHost, port), HEADER_TIMEOUT_MS)
            raw.soTimeout = HEADER_TIMEOUT_MS

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, remoteHost, port, true) as SSLSocket
            tls.useClientMode = true
            tls.tcpNoDelay = true
            tls.soTimeout = HEADER_TIMEOUT_MS
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tls.startHandshake()
            return tls
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun withProxyAuthorization(header: String): String {
        val lines = header.split("\r\n")
        val filtered = lines
            .filterNot { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            .filterNot { it.startsWith("Proxy-Connection:", ignoreCase = true) }
            .filterNot(String::isEmpty)
            .toMutableList()
        if (username.isNotBlank()) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.ISO_8859_1))
            filtered += "Proxy-Authorization: Basic $token"
        }
        filtered += "Proxy-Connection: Keep-Alive"
        return filtered.joinToString("\r\n") + "\r\n\r\n"
    }

    private fun readHeader(socket: Socket): String? {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream(1024)
        var state = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return buffer.toString(Charsets.ISO_8859_1.name())
        }
        throw IOException("Proxy HTTP header exceeded $MAX_HEADER_BYTES bytes")
    }

    private fun writeLocalError(socket: Socket, code: Int, text: String) {
        val body = "$code $text"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $code $text\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n$body"
        socket.outputStream.write(response.toByteArray(Charsets.ISO_8859_1))
        socket.outputStream.flush()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private data class ProxyConnection(
        val socket: Socket,
        val responseHeader: String,
        val transport: UpstreamTransport,
    )

    internal enum class UpstreamTransport(val label: String) {
        PLAIN_HTTP("HTTP proxy"),
        TLS("HTTPS/TLS proxy"),
    }

    internal data class UpstreamEndpoint(val port: Int, val transport: UpstreamTransport)

    private class ProxyRejectedException(message: String) : IOException(message)

    companion object {
        private const val HEADER_TIMEOUT_MS = 8_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private val TLS_FIRST_PORTS = setOf(443, 8443)
        private val LEGACY_EU_SUPERPROXY = Regex("supervpn-dc-eu-[0-9]+\\.mysterium\\.network", RegexOption.IGNORE_CASE)

        internal fun upstreamEndpoints(host: String, port: Int): List<UpstreamEndpoint> = buildList {
            if (port == 8080 && LEGACY_EU_SUPERPROXY.matches(host.trim())) {
                add(UpstreamEndpoint(443, UpstreamTransport.TLS))
            }
            val transports = if (port in TLS_FIRST_PORTS) {
                listOf(UpstreamTransport.TLS, UpstreamTransport.PLAIN_HTTP)
            } else {
                listOf(UpstreamTransport.PLAIN_HTTP, UpstreamTransport.TLS)
            }
            transports.forEach { add(UpstreamEndpoint(port, it)) }
        }
        private val sharedLock = Any()
        private var sharedKey: String? = null
        private var sharedBridge: JoynMysteriumProxyBridge? = null

        fun shared(config: JoynProxyConfig, onFailure: (String) -> Unit): InetSocketAddress = synchronized(sharedLock) {
            val key = "${config.host}:${config.port}:${config.username}:${config.password}"
            if (sharedBridge == null || sharedKey != key) {
                sharedBridge?.close()
                sharedBridge = JoynMysteriumProxyBridge(
                    remoteHost = config.host.trim(),
                    remotePort = config.port,
                    username = config.username,
                    password = config.password,
                    onFailure = onFailure,
                )
                sharedKey = key
            }
            requireNotNull(sharedBridge).localAddress
        }

        fun stopShared() = synchronized(sharedLock) {
            sharedBridge?.close()
            sharedBridge = null
            sharedKey = null
        }
    }
}
