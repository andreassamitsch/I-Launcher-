package com.andreassamitsch.ilauncher.data.epg

import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlTvParserTest {
    @Test
    fun `streams selected xmltv channel with metadata and episode numbers`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260809070000 +0200" stop="20260809080000 +0200" channel="ORF1.at">
                <title lang="en">English title</title>
                <title lang="de">Die Serie</title>
                <sub-title lang="de">Die Folge</sub-title>
                <desc lang="de">Beschreibung &amp; Details</desc>
                <category lang="de">Serie</category>
                <episode-num system="xmltv_ns">0.4/12.</episode-num>
                <date>2026</date>
                <icon src="https://example.test/program.jpg" />
              </programme>
              <programme start="20260809070000 +0200" stop="20260809080000 +0200" channel="IGNORED.de">
                <title>Ignoriert</title>
              </programme>
            </tv>
        """.trimIndent()

        val programmes = XmlTvParser.parse(
            input = ByteArrayInputStream(xml.toByteArray()),
            interestedChannelIds = setOf("ORF1.at"),
            windowStartUtcMillis = Instant.parse("2026-08-09T04:00:00Z").toEpochMilli(),
            windowEndUtcMillis = Instant.parse("2026-08-09T08:00:00Z").toEpochMilli(),
        )

        val programme = programmes.single()
        assertEquals("ORF1.at", programme.xmltvChannelId)
        assertEquals("Die Serie", programme.title)
        assertEquals("Die Folge", programme.subtitle)
        assertEquals("Beschreibung & Details", programme.description)
        assertEquals(listOf("Serie"), programme.categories)
        assertEquals(1, programme.seasonNumber)
        assertEquals(5, programme.episodeNumber)
        assertEquals(2026, programme.releaseYear)
        assertEquals("https://example.test/program.jpg", programme.imageUri)
        assertEquals(Instant.parse("2026-08-09T05:00:00Z").toEpochMilli(), programme.startUtcMillis)
        assertEquals(Instant.parse("2026-08-09T06:00:00Z").toEpochMilli(), programme.stopUtcMillis)
    }
}
