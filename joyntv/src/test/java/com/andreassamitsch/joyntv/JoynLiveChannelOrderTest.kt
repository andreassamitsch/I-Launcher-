package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Test

class JoynLiveChannelOrderTest {
    @Test
    fun `main channels are grouped AT then DE then CH before additional channels`() {
        val source = listOf(
            JoynCountry.AT to channel("Nischensender AT 1"),
            JoynCountry.AT to channel("ORF 2 HD"),
            JoynCountry.AT to channel("Nischensender AT 2"),
            JoynCountry.DE to channel("Kleiner Sender DE 1"),
            JoynCountry.DE to channel("ZDF"),
            JoynCountry.DE to channel("Das Erste HD"),
            JoynCountry.DE to channel("Kleiner Sender DE 2"),
            JoynCountry.CH to channel("Nische CH 1"),
            JoynCountry.CH to channel("SRF zwei"),
            JoynCountry.CH to channel("SRF 1 HD"),
            JoynCountry.CH to channel("Nische CH 2"),
            JoynCountry.AT to channel("ORF 1"),
        )

        val sorted = JoynLiveChannelOrder.sort(source)

        assertEquals(
            listOf(
                "ORF 1",
                "ORF 2 HD",
                "Das Erste HD",
                "ZDF",
                "SRF 1 HD",
                "SRF zwei",
                "Nischensender AT 1",
                "Nischensender AT 2",
                "Kleiner Sender DE 1",
                "Kleiner Sender DE 2",
                "Nische CH 1",
                "Nische CH 2",
            ),
            sorted.map { it.second.title },
        )
    }

    @Test
    fun `german original wins for duplicated german channel`() {
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
    fun `austrian original wins for duplicated austrian channel`() {
        val source = listOf(
            JoynCountry.DE to channel("ServusTV Deutschland"),
            JoynCountry.AT to channel("ServusTV HD"),
            JoynCountry.CH to channel("ServusTV Schweiz"),
        )

        val sorted = JoynLiveChannelOrder.sort(source)

        assertEquals(1, sorted.size)
        assertEquals(JoynCountry.AT, sorted.single().first)
        assertEquals("ServusTV HD", sorted.single().second.title)
    }

    @Test
    fun `additional channels keep Joyn order inside each country`() {
        val source = listOf(
            JoynCountry.CH to channel("CH Sender 1"),
            JoynCountry.DE to channel("DE Sender 1"),
            JoynCountry.AT to channel("AT Sender 1"),
            JoynCountry.CH to channel("CH Sender 2"),
            JoynCountry.AT to channel("AT Sender 2"),
            JoynCountry.DE to channel("DE Sender 2"),
        )

        assertEquals(
            listOf(
                "AT Sender 1",
                "AT Sender 2",
                "DE Sender 1",
                "DE Sender 2",
                "CH Sender 1",
                "CH Sender 2",
            ),
            JoynLiveChannelOrder.sort(source).map { it.second.title },
        )
    }

    private fun channel(title: String) = JoynLiveChannel(
        id = title,
        title = title,
        type = "LINEAR",
    )
}
