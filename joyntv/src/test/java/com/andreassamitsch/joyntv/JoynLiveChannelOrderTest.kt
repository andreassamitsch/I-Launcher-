package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Test

class JoynLiveChannelOrderTest {
    @Test
    fun `main channels of all countries are promoted before remaining channels`() {
        val source = listOf(
            JoynCountry.AT to channel("Nischensender AT"),
            JoynCountry.AT to channel("ORF 2 HD"),
            JoynCountry.DE to channel("Kleiner Sender DE"),
            JoynCountry.DE to channel("Das Erste HD"),
            JoynCountry.CH to channel("Nische CH"),
            JoynCountry.CH to channel("SRF 1 HD"),
            JoynCountry.AT to channel("ORF 1"),
            JoynCountry.DE to channel("ZDF"),
            JoynCountry.CH to channel("SRF zwei"),
        )

        val sorted = JoynLiveChannelOrder.sort(source)

        assertEquals(
            listOf(
                "ORF 1",
                "Das Erste HD",
                "SRF 1 HD",
                "ORF 2 HD",
                "ZDF",
                "SRF zwei",
                "Nischensender AT",
                "Kleiner Sender DE",
                "Nische CH",
            ),
            sorted.map { it.second.title },
        )
    }

    @Test
    fun `remaining channels retain their original Joyn order`() {
        val source = listOf(
            JoynCountry.DE to channel("Sender C"),
            JoynCountry.AT to channel("Sender A"),
            JoynCountry.CH to channel("Sender B"),
        )

        assertEquals(source, JoynLiveChannelOrder.sort(source))
    }

    private fun channel(title: String) = JoynLiveChannel(
        id = title,
        title = title,
        type = "LINEAR",
    )
}
