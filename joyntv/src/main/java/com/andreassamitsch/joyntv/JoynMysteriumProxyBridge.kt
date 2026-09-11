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

/**
 * Local unauthenticated CONNECT bridge for Mysterium's short-lived authenticated HTTP proxies.
 *
 * Joyn clients in the app can keep using Android's process ProxySelector without each networking
 * stack knowing Mysterium credentials. The bridge listens only on loopback, injects the current
 * lease's Proxy-Authorization header upstream and relays the encrypted Joyn tunnel byte-for-byte.
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
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS
            val requestHeader = readHeader(client) ?: return
            if (!requestHeader.startsWith("CONNECT ", ignoreCase = true)) {
                writeLocalError(client, 405, "CONNECT required")
                return
            }

            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(remoteHost, remotePort), HEADER_TIMEOUT_MS)
            remote.soTimeout = HEADER_TIMEOUT_MS
            remote.outputStream.write(withProxyAuthorization(requestHeader).toByteArray(Charsets.ISO_8859_1))
            remote.outputStream.flush()

            val responseHeader = readHeader(remote) ?: throw IOException("Mysterium proxy sent no response")
            client.outputStream.write(responseHeader.toByteArray(Charsets.ISO_8859_1))
            client.outputStream.flush()
            val successful = responseHeader.startsWith("HTTP/1.1 200") || responseHeader.startsWith("HTTP/1.0 200")
            if (!successful) {
                onFailure("Mysterium proxy rejected CONNECT: ${responseHeader.lineSequence().firstOrNull().orEmpty()}")
                return
            }

            client.soTimeout = 0
            remote.soTimeout = 0
            val upstreamSocket = remote
            val upstream = workers.submit {
                try {
                    client.getInputStream().copyTo(upstreamSocket.getOutputStream(), 16 * 1024)
                    runCatching { upstreamSocket.shutdownOutput() }
                } catch (_: Throwable) {
                }
            }
            try {
                upstreamSocket.getInputStream().copyTo(client.getOutputStream(), 16 * 1024)
            } finally {
                upstream.cancel(true)
            }
        } catch (error: Throwable) {
            onFailure(error.message ?: error.javaClass.simpleName)
            runCatching { writeLocalError(client, 502, "Mysterium proxy unavailable") }
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
        }
    }

    private fun withProxyAuthorization(header: String): String {
        val lines = header.split("\r\n")
        val filtered = lines
            .filterNot { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
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
        return null
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

    companion object {
        private const val HEADER_TIMEOUT_MS = 8_000
        private const val MAX_HEADER_BYTES = 32 * 1024
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
