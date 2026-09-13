package com.andreassamitsch.joyntv

import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections

// PC-only stand-in for the Android settings model; the actual production bridge is compiled unchanged.
internal data class JoynProxyConfig(val host: String, val port: Int, val username: String, val password: String)

private fun header(socket: Socket): String {
    val bytes = ArrayList<Byte>()
    while (bytes.size < 32768) {
        val value = socket.getInputStream().read()
        check(value >= 0) { "Unexpected EOF" }
        bytes.add(value.toByte())
        if (bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) {
            return bytes.toByteArray().toString(Charsets.ISO_8859_1)
        }
    }
    error("Oversized header")
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "live") {
        JoynMysteriumProxyBridge(
            System.getenv("MYSTERIUM_PROXY_HOST"), System.getenv("MYSTERIUM_PROXY_PORT").toInt(),
            System.getenv("MYSTERIUM_PROXY_USERNAME"), System.getenv("MYSTERIUM_PROXY_PASSWORD"),
            { System.err.println("BRIDGE_FAILURE: $it") },
        ).use { bridge ->
            println("LOCAL_PROXY_PORT=${bridge.localAddress.port}")
            System.out.flush()
            readlnOrNull()
        }
        return
    }
    val tls = JoynMysteriumProxyBridge.UpstreamTransport.TLS
    val plain = JoynMysteriumProxyBridge.UpstreamTransport.PLAIN_HTTP
    val eu = JoynMysteriumProxyBridge.upstreamEndpoints("supervpn-dc-eu-02.mysterium.network", 8080)
    check(eu.map { it.port to it.transport } == listOf(443 to tls, 8080 to plain, 8080 to tls))
    for (host in listOf("127.0.0.1", "manual.example", "supervpn-dc-eu-02.mysterium.network.evil.example")) {
        check(JoynMysteriumProxyBridge.upstreamEndpoints(host, 8080).map { it.port } == listOf(8080, 8080))
    }
    check(JoynMysteriumProxyBridge.upstreamEndpoints("manual.example", 8443).first().transport == tls)
    val failures = Collections.synchronizedList(mutableListOf<String>())
    val captured = Collections.synchronizedList(mutableListOf<String>())
    val serverErrors = Collections.synchronizedList(mutableListOf<Throwable>())
    ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress()).use { server ->
        server.soTimeout = 5000
        val worker = Thread {
            try {
                for (status in listOf(200, 200, 407)) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        captured.add(header(socket))
                        socket.getOutputStream().write("HTTP/1.1 $status Fixture\r\n\r\n".toByteArray())
                        if (status == 200) {
                            check(socket.getInputStream().readNBytes(4).contentEquals("ping".toByteArray()))
                            socket.getOutputStream().write("pong".toByteArray())
                        }
                    }
                }
            } catch (error: Throwable) { serverErrors.add(error) }
        }.apply { isDaemon = true; start() }
        JoynMysteriumProxyBridge("127.0.0.1", server.localPort, "fixture-user", "fixture-password", failures::add).use { bridge ->
            for (status in listOf(200, 200, 502)) {
                Socket(bridge.localAddress.address, bridge.localAddress.port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write(("CONNECT api.joyn.de:443 HTTP/1.1\r\nHost: api.joyn.de:443\r\n" +
                        "Proxy-Authorization: Basic downstream-must-be-removed\r\n\r\n").toByteArray())
                    check(header(client).startsWith("HTTP/1.1 $status"))
                    if (status == 200) {
                        client.getOutputStream().write("ping".toByteArray())
                        check(client.getInputStream().readNBytes(4).contentEquals("pong".toByteArray()))
                    }
                }
            }
        }
        worker.join(6000)
        check(!worker.isAlive && serverErrors.isEmpty()) { "Fixture server failure" }
    }
    check(captured.size == 3)
    val auth = Base64.getEncoder().encodeToString("fixture-user:fixture-password".toByteArray(Charsets.ISO_8859_1))
    captured.forEach {
        check(it.contains("Proxy-Authorization: Basic $auth\r\n"))
        check(!it.contains("downstream-must-be-removed"))
        check(it.contains("Proxy-Connection: Keep-Alive\r\n"))
    }
    check(failures.size == 1 && failures.first().contains("407"))
    println("PASS: endpoint selection, 3 authenticated CONNECTs, bidirectional relay, 407 stops retries; no Android runtime")
}
