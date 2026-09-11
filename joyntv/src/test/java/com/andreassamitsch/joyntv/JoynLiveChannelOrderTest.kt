package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Test

class JoynLiveChannelOrderTest {
    @Test
    fun `only curated popular channels remain in AT DE CH order`() {
        val source = listOf(
            JoynCountry.AT to channel("Lindenstraße"),
            JoynCountry.AT to channel("ORF 2 Wien HD"),
            JoynCountry.AT to channel("ORF 2 Steiermark HD"),
            JoynCountry.DE to channel("Kabel Eins Classics"),
            JoynCountry.DE to channel("ZDF"),
            JoynCountry.DE to channel("Das Erste HD"),
            JoynCountry.CH to channel("SRF zwei"),
            JoynCountry.CH to channel("SRF 1 HD"),
            JoynCountry.AT to channel("ORF 1"),
            JoynCountry.DE to channel("SAT.1"),
            JoynCountry.DE to channel("ProSieben"),
            JoynCountry.DE to channel("RTL"),
        )

        val sorted = JoynLiveChannelOrder.sort(source)

        assertEquals(
            listOf(
                "ORF 1",
                "ORF 2 Steiermark HD",
                "Das Erste HD",
                "ZDF",
                "SAT.1",
                "ProSieben",
                "RTL",
                "SRF 1 HD",
                "SRF zwei",
            ),
            sorted.map { it.second.title },
        )
    }

    @Test
    fun `non styrian ORF 2 regional feeds are excluded`() {
        val source = listOf(
            JoynCountry.AT to channel("ORF 2 Wien"),
            JoynCountry.AT to channel("ORF 2 Niederösterreich"),
            JoynCountry.AT to channel("ORF 2 Steiermark"),
            JoynCountry.AT to channel("ORF 2 Tirol"),
        )

        assertEquals(
            listOf("ORF 2 Steiermark"),
            JoynLiveChannelOrder.sort(source).map { it.second.title },
        )
    }

    @Test
    fun `mirrored german channels are not taken from AT or CH`() {
        val source = listOf(
            JoynCountry.AT to channel("ProSieben Österreich"),
            JoynCountry.DE to channel("ProSieben HD"),
            JoynCountry.CH to channel("ProSieben Schweiz"),
        )

        val sorted = JoynLiveChannelOrder.sort(source)

        assertEquals(1, sorted.size)
        assertEquals(JoynCountry.DE, sorted.single().first)
        assertEquals("ProSieben HD", sorted.single().second.title)
    }

    @Test
    fun `thematic variants do not accidentally match a popular base channel`() {
        val source = listOf(
            JoynCountry.DE to channel("Kabel Eins"),
            JoynCountry.DE to channel("Kabel Eins Classics"),
            JoynCountry.DE to channel("Kabel Eins Doku"),
            JoynCountry.DE to channel("ProSieben"),
            JoynCountry.DE to channel("ProSieben FUN"),
        )

        assertEquals(
            listOf("ProSieben", "Kabel Eins"),
            JoynLiveChannelOrder.sort(source).map { it.second.title },
        )
    }

    private fun channel(title: String) = JoynLiveChannel(
        id = title,
        title = title,
        type = "LINEAR",
    )
}
