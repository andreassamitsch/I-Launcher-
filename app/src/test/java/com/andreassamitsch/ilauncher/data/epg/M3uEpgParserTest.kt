package com.andreassamitsch.ilauncher.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uEpgParserTest {
    @Test
    fun `parses xmltv source ids alternates and enigma reference hints without retaining streams`() {
        val serviceRef = "1:0:1:2EE3:441:1:C00000:0:0:0:"
        val source = M3uEpgParser.parse(
            """
            #EXTM3U x-tvg-url="https://example.test/epg/epg.gz"
            #EXTINF:-1 tvg-id="RTL.de" tvg-name="RTL HD" tvg-logo="http://192.168.1.33/picon/$serviceRef.png" tvg-id-ALT="RTL.alt",RTL
            http://192.168.1.33:8001/$serviceRef
            """.trimIndent(),
        )

        assertEquals("https://example.test/epg/epg.gz", source.epgUrl)
        val channel = source.channels.single()
        assertEquals("RTL.de", channel.xmltvChannelId)
        assertEquals(listOf("RTL.alt"), channel.alternateXmltvIds)
        assertEquals("RTL HD", channel.tvgName)
        assertEquals("RTL", channel.displayName)
        assertTrue(
            channel.serviceReferenceHints.contains(
                M3uEpgParser.normalizeServiceReference(serviceRef),
            ),
        )
    }
}
