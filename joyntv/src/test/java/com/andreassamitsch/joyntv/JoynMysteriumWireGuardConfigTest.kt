package com.andreassamitsch.joyntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynMysteriumWireGuardConfigTest {
    @Test
    fun materializeRemovesIpv6AndForcesAppScopedIpv4Default() {
        val input = """
            [Interface]
            PrivateKey = %private_key%
            Address = 10.77.0.2/32, fd00:1234::2/128
            DNS = 10.77.0.1, 2606:4700:4700::1111
            IncludedApplications = old.package

            [Peer]
            PublicKey = provider-public-key
            AllowedIPs = 0.0.0.0/1, 128.0.0.0/1, ::/1, 8000::/1
            Endpoint = 198.51.100.10:51820
        """.trimIndent()

        val output = JoynMysteriumWireGuardConfig.materialize(
            template = input,
            privateKey = "replacement-private-key",
            packageName = "com.andreassamitsch.joyntv",
        )

        assertTrue(output.contains("PrivateKey = replacement-private-key"))
        assertTrue(output.contains("Address = 10.77.0.2/32"))
        assertTrue(output.contains("DNS = 10.77.0.1"))
        assertTrue(output.contains("IncludedApplications = com.andreassamitsch.joyntv"))
        assertTrue(output.contains("AllowedIPs = 0.0.0.0/0"))
        assertFalse(output.contains("fd00:1234::2"))
        assertFalse(output.contains("2606:4700:4700::1111"))
        assertFalse(output.contains("::/1"))
        assertFalse(output.contains("8000::/1"))
        assertFalse(output.contains("old.package"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun materializeRejectsMoreThanOnePeer() {
        JoynMysteriumWireGuardConfig.materialize(
            template = """
                [Interface]
                PrivateKey = %private_key%
                Address = 10.0.0.2/32
                [Peer]
                PublicKey = a
                AllowedIPs = 0.0.0.0/0
                [Peer]
                PublicKey = b
                AllowedIPs = 0.0.0.0/0
            """.trimIndent(),
            privateKey = "key",
            packageName = "com.andreassamitsch.joyntv",
        )
    }
}
