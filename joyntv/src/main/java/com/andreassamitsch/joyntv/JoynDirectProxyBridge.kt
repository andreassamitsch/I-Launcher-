package com.andreassamitsch.joyntv

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Loopback-only HTTP CONNECT relay for markets that do not need geo routing.
 *
 * I Launcher intentionally uses the same playback contract for every Joyn fallback: it receives a
 * loopback HTTP-proxy endpoint and sends DASH/DRM traffic through it. For the local AT market no
 * Mysterium exit is required, so this relay opens the CONNECT target directly instead of forwarding
 * the request to an upstream proxy. Keeping the loopback contract stable means I Launcher does not
 * need Mysterium credentials or a second media-source implementation for direct Austrian playback.
 *
 * The relay does not terminate target TLS. After CONNECT 200 the caller's encrypted HTTPS stream is
 * copied byte-for-byte between I Launcher and the Joyn/CDN host.
 */
internal class JoynDirectProxyBridge : Closeable {
    private val server = ServerSocket()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "joyn-direct-proxy-worker").apply { isDaemon = true }
    }

    @Volatile
    private var running = true

    val localAddress: InetSocketAddress

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        localAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
        Thread(::acceptLoop, "joyn-direct-proxy-accept").apply {
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
        var target: Socket? = null
        var tunnelEstablished = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val requestHeader = readHeader(client) ?: return
            val connectTarget = parseConnectTarget(requestHeader)
            if (connectTarget == null) {
                writeLocalError(client, 405, "CONNECT required")
                return
            }

            target = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(connectTarget.host, connectTarget.port), CONNECT_TIMEOUT_MS)
                soTimeout = 0
            }

            client.outputStream.write(CONNECT_OK.toByteArray(Charsets.ISO_8859_1))
            client.outputStream.flush()
            tunnelEstablished = true
            client.soTimeout = 0

            val targetSocket = target
            val upstream = workers.submit {
                try {
                    client.getInputStream().copyTo(targetSocket.getOutputStream(), BUFFER_SIZE)
                    runCatching { targetSocket.shutdownOutput() }
                } catch (_: Throwable) {
                    // Normal when Media3 cancels a request or closes an idle connection.
                }
            }
            try {
                targetSocket.getInputStream().copyTo(client.getOutputStream(), BUFFER_SIZE)
            } finally {
                upstream.cancel(true)
            }
        } catch (_: Throwable) {
            if (!tunnelEstablished) {
                runCatching { writeLocalError(client, 502, "Direct target unavailable") }
            }
        } finally {
            runCatching { target?.close() }
            runCatching { client.close() }
        }
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
        val response =
            "HTTP/1.1 $code $text\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n$body"
        socket.outputStream.write(response.toByteArray(Charsets.ISO_8859_1))
        socket.outputStream.flush()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        workers.shutdownNow()
    }

    internal data class ConnectTarget(val host: String, val port: Int)

    companion object {
        private const val HEADER_TIMEOUT_MS = 8_000
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val BUFFER_SIZE = 16 * 1024
        private const val CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n"

        internal fun parseConnectTarget(header: String): ConnectTarget? {
            val firstLine = header.lineSequence().firstOrNull()?.trim().orEmpty()
            val parts = firstLine.split(Regex("\\s+"))
            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) return null
            val authority = parts[1].trim()
            if (authority.isBlank()) return null

            val host: String
            val portText: String
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close <= 1 || close + 2 > authority.length || authority.getOrNull(close + 1) != ':') {
                    return null
                }
                host = authority.substring(1, close)
                portText = authority.substring(close + 2)
            } else {
                val separator = authority.lastIndexOf(':')
                if (separator <= 0 || separator == authority.lastIndex) return null
                host = authority.substring(0, separator)
                portText = authority.substring(separator + 1)
            }

            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return ConnectTarget(host = host, port = port)
        }
    }
}
