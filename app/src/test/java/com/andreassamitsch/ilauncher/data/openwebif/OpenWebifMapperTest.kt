package com.andreassamitsch.ilauncher.data.openwebif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenWebifMapperTest {
    @Test fun `maps bouquet order unchanged`() {
        val bouquets = OpenWebifMapper.bouquets(
            listOf(
                OpenWebifServiceDto(serviceName = "Favoriten", serviceReference = "bq:1"),
                OpenWebifServiceDto(serviceName = "HD", serviceReference = "bq:2"),
            ),
        )

        assertEquals(listOf("Favoriten", "HD"), bouquets.map { it.name })
    }

    @Test fun `maps current and next EPG to matching service`() {
        val nowMillis = 1_000_000L
        val channelRef = "1:0:1:ABC:DEF:1:0:0:0:0:"
        val services = listOf(
            OpenWebifServiceDto(
                serviceName = "ORF 1",
                serviceReference = channelRef,
                picon = "/picon/orf1.png",
            ),
        )
        val events = listOf(
            OpenWebifEventDto(
                id = 10,
                beginTimestamp = 100,
                durationSec = 100,
                title = "Alt",
                sref = channelRef,
            ),
            OpenWebifEventDto(
                id = 11,
                beginTimestamp = 950,
                durationSec = 100,
                title = "Jetzt",
                sref = channelRef,
            ),
            OpenWebifEventDto(
                id = 12,
                beginTimestamp = 1_050,
                durationSec = 100,
                title = "Danach",
                sref = channelRef,
            ),
        )

        val channel = OpenWebifMapper.channels(
            baseUrl = "http://192.168.1.20/",
            services = services,
            events = events,
            nowUtcMillis = nowMillis,
        ).single()

        assertEquals("Jetzt", channel.now?.title)
        assertEquals("Danach", channel.next?.title)
        assertEquals("http://192.168.1.20/picon/orf1.png", channel.piconUri)
        assertNotNull(channel.progressFraction(nowMillis))
        assertEquals(0.5f, channel.progressFraction(nowMillis)!!, 0.001f)
    }

    @Test fun `decodes HTML entities and escaped newlines in EPG text`() {
        val nowMillis = 1_000_000L
        val channelRef = "1:0:1:ATV:HD:1:0:0:0:0:"
        val services = listOf(
            OpenWebifServiceDto(
                serviceName = "ATV HD",
                serviceReference = channelRef,
            ),
        )
        val events = listOf(
            OpenWebifEventDto(
                id = 21,
                beginTimestamp = 950,
                durationSec = 200,
                title = "You&amp;#x27;re cordially invited",
                shortdesc = "Zeile 1\\nZeile 2 &amp; mehr",
                longdesc = "Absatz 1\\n\\nAbsatz 2 &quot;Zitat&quot;",
                sref = channelRef,
            ),
        )

        val program = OpenWebifMapper.channels(
            baseUrl = "http://192.168.1.20/",
            services = services,
            events = events,
            nowUtcMillis = nowMillis,
        ).single().now!!

        assertEquals("You're cordially invited", program.title)
        assertEquals("Zeile 1\nZeile 2 & mehr", program.shortDescription)
        assertEquals("Absatz 1\n\nAbsatz 2 \"Zitat\"", program.longDescription)
    }
}
