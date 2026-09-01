package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ServusSourceTimestampPolicyTest {
    @Test
    fun servusAvailabilityTimestampIsUsed() {
        val card = ServusCardDto(
            id = "episode",
            sunriseTimestamp = "2026-09-01T07:15:00Z",
            startTime = "2026-09-01T08:00:00Z",
        )

        assertEquals(
            Instant.parse("2026-09-01T07:15:00Z").toEpochMilli(),
            ServusSourceTimestampPolicy.resolve(card, Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun broadcastStartIsNeverUsedAsVodPublicationTime() {
        val card = ServusCardDto(
            id = "episode",
            startTime = "2026-09-01T19:20:00+02:00",
        )

        assertNull(
            ServusSourceTimestampPolicy.resolve(card, Instant.parse("2026-09-01T20:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun timeEmbeddedInNewsTitleIsNeverUsedAsVodPublicationTime() {
        val card = ServusCardDto(
            id = "episode",
            title = "Nachrichten 19:20 | 31.08.",
        )

        assertNull(
            ServusSourceTimestampPolicy.resolve(card, Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun importTimeIsNeverUsedAsPublicationFallback() {
        val importTime = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()
        val card = ServusCardDto(
            id = "episode",
            title = "Eine Sendung ohne Quellzeitpunkt",
        )

        assertNull(ServusSourceTimestampPolicy.resolve(card, importTime))
    }
}
