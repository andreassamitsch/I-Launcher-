package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynMysteriumProxyBridgeTest {
    @Test
    fun legacyEuSuperproxyReturnedOn8080TriesTls443First() {
        val endpoints = JoynMysteriumProxyBridge.upstreamEndpoints(
            host = "supervpn-dc-eu-01.mysterium.network",
            port = 8080,
        )

        assertEquals(443, endpoints.first().port)
        assertEquals(
            JoynMysteriumProxyBridge.UpstreamTransport.TLS,
            endpoints.first().transport,
        )
    }

    @Test
    fun privateCaFallbackIsRestrictedToKnownMysteriumEuSuperproxyOn443() {
        assertTrue(
            JoynMysteriumProxyBridge.privateCaFallbackAllowed(
                "supervpn-dc-eu-01.mysterium.network",
                443,
            ),
        )
        assertTrue(
            JoynMysteriumProxyBridge.privateCaFallbackAllowed(
                "SUPERVPN-DC-EU-12.MYSTERIUM.NETWORK",
                443,
            ),
        )
        assertFalse(
            JoynMysteriumProxyBridge.privateCaFallbackAllowed(
                "supervpn-dc-eu-01.mysterium.network",
                8080,
            ),
        )
        assertFalse(
            JoynMysteriumProxyBridge.privateCaFallbackAllowed(
                "example.com",
                443,
            ),
        )
        assertFalse(
            JoynMysteriumProxyBridge.privateCaFallbackAllowed(
                "evil.supervpn-dc-eu-01.mysterium.network.example",
                443,
            ),
        )
    }
}
