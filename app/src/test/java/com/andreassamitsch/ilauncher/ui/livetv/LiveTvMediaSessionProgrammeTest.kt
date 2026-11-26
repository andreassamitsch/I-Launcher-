package com.andreassamitsch.ilauncher.ui.livetv

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvMediaSessionProgrammeTest {
    private val first = LiveTvProgram(1, "Erste Sendung", startUtcMillis = 100L, durationMillis = 100L)
    private val second = LiveTvProgram(2, "Zweite Sendung", startUtcMillis = 200L, durationMillis = 100L)
    private val channel = LiveTvChannel("service", "ORF 1", "picon", first, second)

    @Test
    fun guideCurrentProgrammeWinsAndSwitchesExactlyAtBoundary() {
        val enriched = second.copy(subtitle = "Folge 2")
        assertEquals(first.title, liveTvSessionProgramme(channel, listOf(first, enriched), 199L).title)
        val value = liveTvSessionProgramme(channel, listOf(first, enriched), 200L)
        assertEquals(second.title, value.title)
        assertEquals("ORF 1", value.channelName)
        assertEquals("Folge 2", value.subtitle)
        assertEquals("service", value.serviceReference)
    }

    @Test
    fun fallsBackToCurrentReceiverNextWhenGuideMissing() {
        assertEquals(second.title, liveTvSessionProgramme(channel, emptyList(), 200L).title)
    }

    @Test
    fun staleProgrammeIsNotPublished() {
        val value = liveTvSessionProgramme(channel, listOf(first, second), 300L)
        assertEquals(channel.name, value.title)
        assertEquals("picon", value.artworkUri)
        assertNull(value.subtitle)
    }

    @Test
    fun missingProgrammeUsesChannelIdentity() {
        assertEquals("ORF 1", liveTvSessionProgramme(channel.copy(now = null, next = null), emptyList(), 100L).title)
    }
}
