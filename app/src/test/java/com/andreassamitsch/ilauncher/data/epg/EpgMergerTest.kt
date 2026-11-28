package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMergerTest {
    @Test
    fun `keeps openwebif timing while adding xmltv metadata`() {
        val now = 10_000_000L
        val base = LiveTvProgram(
            eventId = 77,
            title = "Die Serie",
            startUtcMillis = now - 20 * 60_000L,
            durationMillis = 60 * 60_000L,
        )
        val channel = LiveTvChannel(
            serviceReference = "1:0:1:ABC:DEF:1:C00000:0:0:0:",
            name = "Test HD",
            now = base,
        )
        val xml = XmlTvProgram(
            xmltvChannelId = "Test.de",
            startUtcMillis = base.startUtcMillis + 60_000L,
            stopUtcMillis = base.endUtcMillis + 60_000L,
            title = "Die Serie",
            subtitle = "Episode fünf",
            description = "Lange Beschreibung",
            categories = listOf("Serie"),
            seasonNumber = 1,
            episodeNumber = 5,
            imageUri = "https://example.test/still.jpg",
        )

        val result = EpgMerger.merge(
            channels = listOf(channel),
            mappings = listOf(
                EpgChannelMapping(
                    serviceReference = channel.serviceReference,
                    xmltvChannelId = "Test.de",
                    matchMethod = EpgChannelMatcher.METHOD_EXACT_NAME,
                    confidence = 0.98f,
                ),
            ),
            programmes = listOf(xml),
            nowUtcMillis = now,
        )

        val merged = result.channels.single().now!!
        assertEquals(77L, merged.eventId)
        assertEquals(base.startUtcMillis, merged.startUtcMillis)
        assertEquals(base.durationMillis, merged.durationMillis)
        assertEquals("Episode fünf", merged.subtitle)
        assertEquals("Lange Beschreibung", merged.longDescription)
        assertEquals(1, merged.seasonNumber)
        assertEquals(5, merged.episodeNumber)
        assertEquals("https://example.test/still.jpg", merged.imageUri)
        assertEquals("Test.de", merged.xmltvChannelId)
        assertEquals(1, result.guideByServiceReference[channel.serviceReference]?.size)
        assertNull(result.channels.single().next)
    }

    @Test
    fun `placeholder never overrides current XMLTV and incomplete refresh retains valid airing`() {
        val now = 100000L
        val placeholder = LiveTvProgram(null, "No event", startUtcMillis = 0, durationMillis = 0)
        val receiver = LiveTvChannel("ref", "Sender", now = placeholder)
        val xml = XmlTvProgram("test", 90000L, 120000L, "Live", imageUri = "epg")
        val mapping = EpgChannelMapping("ref", "test", EpgChannelMatcher.METHOD_EXACT_NAME, 1f)
        val merged = EpgMerger.merge(listOf(receiver), listOf(mapping), listOf(xml), now).channels.single()
        assertEquals("epg", merged.now?.imageUri)
        assertEquals("Live", advanceEpgChannel(receiver, merged, emptyList(), now).now?.title)
        assertNull(advanceEpgChannel(receiver, merged, emptyList(), 120000L).now)
    }

    @Test
    fun `does not merge different current programmes on weak time overlap`() {
        val now = 10_000_000L
        val base = LiveTvProgram(
            eventId = 88,
            title = "Nachrichten",
            startUtcMillis = now - 5 * 60_000L,
            durationMillis = 60 * 60_000L,
        )
        val channel = LiveTvChannel(
            serviceReference = "1:0:1:ABC:DEF:1:C00000:0:0:0:",
            name = "Test HD",
            now = base,
        )
        val xml = XmlTvProgram(
            xmltvChannelId = "Test.de",
            startUtcMillis = now - 15 * 60_000L,
            stopUtcMillis = now + 60_000L,
            title = "Wetter",
            description = "Darf nicht übernommen werden",
        )

        val result = EpgMerger.merge(
            channels = listOf(channel),
            mappings = listOf(
                EpgChannelMapping(
                    serviceReference = channel.serviceReference,
                    xmltvChannelId = "Test.de",
                    matchMethod = EpgChannelMatcher.METHOD_EXACT_NAME,
                    confidence = 0.98f,
                ),
            ),
            programmes = listOf(xml),
            nowUtcMillis = now,
        )

        val merged = result.channels.single().now!!
        assertEquals("Nachrichten", merged.title)
        assertNull(merged.longDescription)
        assertNull(merged.xmltvChannelId)
    }
}
