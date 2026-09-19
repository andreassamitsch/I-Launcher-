package com.andreassamitsch.ilauncher.data.joyn

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class JoynLiveChannelMatcherTest {
    @Test
    fun `quality preferred ProSieben uses swiss Joyn even for Austria bouquet suffix`() {
        val sat = channel("1:0:19:1111:0:0:0:0:0:0:", "ProSieben Austria HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(sat),
            joynChannels = listOf(
                joyn("multi:CH:pro7", "ProSieben", "CH"),
                joyn("multi:AT:pro7", "ProSieben", "AT"),
                joyn("multi:DE:pro7", "ProSieben", "DE"),
            ),
        )

        assertEquals("multi:CH:pro7", result[sat.serviceReference]?.id)
    }

    @Test
    fun `quality preferred TLC uses swiss Joyn when available`() {
        val sat = channel("1:0:19:1222:0:0:0:0:0:0:", "TLC HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(sat),
            joynChannels = listOf(
                joyn("multi:AT:tlc", "TLC", "AT"),
                joyn("multi:CH:tlc", "TLC", "CH"),
            ),
        )

        assertEquals("multi:CH:tlc", result[sat.serviceReference]?.id)
    }

    @Test
    fun `Nitro matches RTL Nitro and prefers swiss feed`() {
        val sat = channel("1:0:19:1224:0:0:0:0:0:0:", "NITRO HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(sat),
            joynChannels = listOf(
                joyn("multi:AT:nitro", "NITRO Austria", "AT"),
                joyn("multi:CH:nitro", "RTL NITRO Schweiz", "CH"),
            ),
        )

        assertEquals("multi:CH:nitro", result[sat.serviceReference]?.id)
    }

    @Test
    fun `country suffixes are ignored when choosing the swiss variant`() {
        val sat = channel("1:0:19:1223:0:0:0:0:0:0:", "TLC Austria HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(sat),
            joynChannels = listOf(
                joyn("multi:AT:tlc", "TLC Austria", "AT"),
                joyn("multi:CH:tlc", "TLC Schweiz", "CH"),
            ),
        )

        assertEquals("multi:CH:tlc", result[sat.serviceReference]?.id)
    }

    @Test
    fun `TV Sat naming aliases map to Joyn station families`() {
        val bouquet = listOf(
            channel("atv2", "ATV2 HD"),
            channel("p7maxx", "Pro7 MAXX HD"),
            channel("orfst", "ORF2St HD"),
            channel("mdr", "MDR S-Anhalt HD"),
            channel("ndr", "NDR FS NDS HD"),
            channel("swr", "SWR BW HD"),
            channel("br", "BR Süd HD"),
            channel("euronews", "EURONEWS GERMAN SD"),
        )
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = bouquet,
            joynChannels = listOf(
                joyn("multi:AT:atv2", "ATV II", "AT"),
                joyn("multi:CH:p7maxx", "ProSieben MAXX Schweiz", "CH"),
                joyn("multi:AT:orfst", "ORF St", "AT"),
                joyn("multi:DE:mdr", "MDR Sachsen-Anhalt", "DE"),
                joyn("multi:DE:ndr", "NDR Niedersachsen", "DE"),
                joyn("multi:DE:swr", "SWR Baden-Württemberg", "DE"),
                joyn("multi:DE:br", "BR Fernsehen Süd", "DE"),
                joyn("multi:AT:euronews", "Euronews", "AT"),
            ),
        )

        assertEquals("multi:AT:atv2", result["atv2"]?.id)
        assertEquals("multi:CH:p7maxx", result["p7maxx"]?.id)
        assertEquals("multi:AT:orfst", result["orfst"]?.id)
        assertEquals("multi:DE:mdr", result["mdr"]?.id)
        assertEquals("multi:DE:ndr", result["ndr"]?.id)
        assertEquals("multi:DE:swr", result["swr"]?.id)
        assertEquals("multi:DE:br", result["br"]?.id)
        assertEquals("multi:AT:euronews", result["euronews"]?.id)
    }

    @Test
    fun `swiss bouquet suffix prefers swiss Joyn variant`() {
        val sat = channel("1:0:19:2222:0:0:0:0:0:0:", "ProSieben Schweiz HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(sat),
            joynChannels = listOf(
                joyn("multi:AT:pro7", "ProSieben", "AT"),
                joyn("multi:CH:pro7", "ProSieben", "CH"),
            ),
        )

        assertEquals("multi:CH:pro7", result[sat.serviceReference]?.id)
    }

    @Test
    fun `Austria bouquet label still prefers swiss Joyn when same station exists there`() {
        val puls4 = channel("1:0:19:3333:0:0:0:0:0:0:", "PULS 4 HD Austria")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(puls4),
            joynChannels = listOf(
                joyn("multi:AT:puls4", "PULS 4 Austria", "AT"),
                joyn("multi:CH:puls4", "PULS 4 Schweiz", "CH"),
            ),
        )

        assertEquals("multi:CH:puls4", result[puls4.serviceReference]?.id)
    }

    @Test
    fun `neutral channel defaults to Austria without adding Joyn-only channels`() {
        val puls4 = channel("1:0:19:3333:0:0:0:0:0:0:", "PULS 4 HD")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(puls4),
            joynChannels = listOf(
                joyn("multi:AT:puls4", "PULS 4", "AT"),
                joyn("multi:AT:sat1", "SAT.1", "AT"),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("multi:AT:puls4", result[puls4.serviceReference]?.id)
        assertFalse(result.values.any { it.id == "multi:AT:sat1" })
    }

    @Test
    fun `common station spelling aliases still require exact canonical match`() {
        val kabel = channel("1:0:19:4444:0:0:0:0:0:0:", "Kabel 1 Austria HD")
        val almost = channel("1:0:19:5555:0:0:0:0:0:0:", "Kabel 1 Doku Austria HD")
        val inventory = listOf(joyn("multi:AT:kabeleins", "Kabel Eins", "AT"))

        val result = JoynLiveChannelMatcher.mapBouquet(listOf(kabel, almost), inventory)

        assertEquals("multi:AT:kabeleins", result[kabel.serviceReference]?.id)
        assertNull(result[almost.serviceReference])
    }

    private fun channel(reference: String, name: String) = LiveTvChannel(
        serviceReference = reference,
        name = name,
    )

    private fun joyn(id: String, title: String, country: String) = JoynBridgeChannel(
        id = id,
        title = title,
        country = country,
    )
}
