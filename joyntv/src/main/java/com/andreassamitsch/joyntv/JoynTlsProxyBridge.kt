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
 * Adapts a TLS/HTTPS forward proxy (such as NordVPN proxy_ssl on port 89) to a local plain
 * HTTP CONNECT proxy. OkHttp/Java's Proxy.Type.HTTP does not establish TLS to the proxy itself,
 * therefore it cannot talk to Nord's port 89 directly.
 *
 * The bridge only listens on 127.0.0.1. It opens a TLS connection to the remote proxy, verifies
 * the proxy certificate/hostname, injects Proxy-Authorization inside that encrypted connection,
 * and then relays the CONNECT tunnel byte-for-byte.
 */
internal class JoynTlsProxyBridge(
    private val remoteHost: String,
    private val remotePort: Int,
    private val username: String,
    private val password: String,
) : Closeable {
    private val server = ServerSocket()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "joyn-tls-proxy-worker").apply { isDaemon = true }
    }
    @Volatile private var running = true

    val localAddress: InetSocketAddress

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        localAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
        Thread(::acceptLoop, "joyn-tls-proxy-accept").apply {
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
        var remote: SSLSocket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val requestHeader = readHeader(client) ?: return
            if (!requestHeader.startsWith("CONNECT ", ignoreCase = true)) {
                writeLocalError(client, 405, "CONNECT required")
                return
            }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            remote = factory.createSocket(remoteHost, remotePort) as SSLSocket
            remote.soTimeout = HEADER_TIMEOUT_MS
            remote.tcpNoDelay = true
            remote.sslParameters = remote.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            remote.startHandshake()

            val outbound = withProxyAuthorization(requestHeader)
            remote.outputStream.write(outbound.toByteArray(Charsets.ISO_8859_1))
            remote.outputStream.flush()

            val responseHeader = readHeader(remote) ?: return
            client.outputStream.write(responseHeader.toByteArray(Charsets.ISO_8859_1))
            client.outputStream.flush()
            if (!responseHeader.startsWith("HTTP/1.1 200") &&
                !responseHeader.startsWith("HTTP/1.0 200")
            ) return

            client.soTimeout = 0
            remote.soTimeout = 0
            val remoteSocket = remote
            val upstream = workers.submit {
                try {
                    client.getInputStream().copyTo(remoteSocket.getOutputStream(), 16 * 1024)
                    runCatching { remoteSocket.shutdownOutput() }
                } catch (_: Throwable) {
                }
            }
            try {
                remoteSocket.getInputStream().copyTo(client.getOutputStream(), 16 * 1024)
            } finally {
                upstream.cancel(true)
            }
        } catch (_: Throwable) {
            runCatching { writeLocalError(client, 502, "Upstream proxy unavailable") }
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
        }
    }

    private fun withProxyAuthorization(header: String): String {
        val lines = header.split("\r\n")
        val filtered = lines.filterNot { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            .filterNot { it.isEmpty() }
            .toMutableList()
        if (username.isNotBlank()) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.ISO_8859_1))
            filtered += "Proxy-Authorization: Basic $token"
        }
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
        return null
    }

    private fun writeLocalError(socket: Socket, code: Int, text: String) {
        val body = "$code $text"
        val response = "HTTP/1.1 $code $text\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
        socket.outputStream.write(response.toByteArray(Charsets.ISO_8859_1))
        socket.outputStream.flush()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        workers.shutdownNow()
    }

    companion object {
        private const val HEADER_TIMEOUT_MS = 8_000
        private const val MAX_HEADER_BYTES = 32 * 1024

        private val sharedLock = Any()
        private var sharedKey: String? = null
        private var sharedBridge: JoynTlsProxyBridge? = null

        fun shared(config: JoynProxyConfig): InetSocketAddress = synchronized(sharedLock) {
            val key = "${config.host}:${config.port}:${config.username}:${config.password}"
            if (sharedBridge == null || sharedKey != key) {
                sharedBridge?.close()
                sharedBridge = JoynTlsProxyBridge(
                    remoteHost = config.host.trim(),
                    remotePort = config.port,
                    username = config.username,
                    password = config.password,
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
