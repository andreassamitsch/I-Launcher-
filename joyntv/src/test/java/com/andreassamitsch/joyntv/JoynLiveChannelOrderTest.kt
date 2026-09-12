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
    fun `rtl family can come from switzerland when germany does not expose it`() {
        val source = listOf(
            JoynCountry.CH to channel("RTL CH HD"),
            JoynCountry.CH to channel("VOX CH"),
            JoynCountry.CH to channel("RTLZWEI CH"),
            JoynCountry.CH to channel("Super RTL CH"),
            JoynCountry.CH to channel("NITRO CH"),
            JoynCountry.CH to channel("RTL UP CH"),
            JoynCountry.CH to channel("VOXup CH"),
            JoynCountry.CH to channel("n-tv CH"),
        )

        assertEquals(
            listOf(
                "RTL CH HD",
                "VOX CH",
                "RTLZWEI CH",
                "Super RTL CH",
                "NITRO CH",
                "RTL UP CH",
                "VOXup CH",
                "n-tv CH",
            ),
            JoynLiveChannelOrder.sort(source).map { it.second.title },
        )
    }

    @Test
    fun `duplicate feeds prefer explicit highest resolution`() {
        val source = listOf(
            JoynCountry.DE to channel("ZDF", quality = "720p"),
            JoynCountry.DE to channel("ZDF", quality = "1080p"),
            JoynCountry.DE to channel("ZDF", quality = "SD"),
        )

        val winner = JoynLiveChannelOrder.sort(source).single().second

        assertEquals("1080p", winner.quality)
    }

    @Test
    fun `duplicate feeds fall back to quality in title`() {
        val source = listOf(
            JoynCountry.DE to channel("SAT.1 SD"),
            JoynCountry.DE to channel("SAT.1 HD"),
        )

        val winner = JoynLiveChannelOrder.sort(source).single().second

        assertEquals("SAT.1 HD", winner.title)
    }

    @Test
    fun `country row keeps all channels and orders known stations before the rest`() {
        val source = listOf(
            channel("Tele 5"),
            channel("RTL CH SD"),
            channel("AAA FAST"),
            channel("SRF 1"),
            channel("RTL CH HD"),
        )

        assertEquals(
            listOf("SRF 1", "RTL CH HD", "AAA FAST", "Tele 5"),
            JoynLiveChannelOrder.sortCountry(JoynCountry.CH, source).map { it.title },
        )
    }

    @Test
    fun `country row places event streams after linear channels`() {
        val source = listOf(
            channel("Event A", type = "EVENT"),
            channel("Zulu TV"),
            channel("Alpha TV"),
        )

        assertEquals(
            listOf("Alpha TV", "Zulu TV", "Event A"),
            JoynLiveChannelOrder.sortCountry(JoynCountry.DE, source).map { it.title },
        )
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

    private fun channel(
        title: String,
        quality: String? = null,
        type: String = "LINEAR",
    ) = JoynLiveChannel(
        id = listOfNotNull(title, quality).joinToString("-"),
        title = title,
        type = type,
        quality = quality,
    )
}
