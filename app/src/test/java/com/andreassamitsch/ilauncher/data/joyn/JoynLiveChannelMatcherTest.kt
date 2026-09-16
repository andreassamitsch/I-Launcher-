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
    fun `non quality preferred Austria station remains Austria`() {
        val puls4 = channel("1:0:19:3333:0:0:0:0:0:0:", "PULS 4 HD Austria")
        val result = JoynLiveChannelMatcher.mapBouquet(
            bouquet = listOf(puls4),
            joynChannels = listOf(
                joyn("multi:AT:puls4", "PULS 4", "AT"),
                joyn("multi:CH:puls4", "PULS 4", "CH"),
            ),
        )

        assertEquals("multi:AT:puls4", result[puls4.serviceReference]?.id)
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
