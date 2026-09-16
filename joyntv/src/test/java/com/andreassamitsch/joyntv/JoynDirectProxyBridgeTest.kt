package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynDirectProxyBridgeTest {
    @Test
    fun `parses normal https CONNECT target`() {
        val target = JoynDirectProxyBridge.parseConnectTarget(
            "CONNECT cdn.example.com:443 HTTP/1.1\r\nHost: cdn.example.com:443\r\n\r\n",
        )

        assertEquals("cdn.example.com", target?.host)
        assertEquals(443, target?.port)
    }

    @Test
    fun `parses bracketed ipv6 CONNECT target`() {
        val target = JoynDirectProxyBridge.parseConnectTarget(
            "CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n\r\n",
        )

        assertEquals("2001:db8::1", target?.host)
        assertEquals(8443, target?.port)
    }

    @Test
    fun `rejects plain http proxy requests and invalid ports`() {
        assertNull(
            JoynDirectProxyBridge.parseConnectTarget(
                "GET https://cdn.example.com/video.mpd HTTP/1.1\r\n\r\n",
            ),
        )
        assertNull(
            JoynDirectProxyBridge.parseConnectTarget(
                "CONNECT cdn.example.com:70000 HTTP/1.1\r\n\r\n",
            ),
        )
    }
}
