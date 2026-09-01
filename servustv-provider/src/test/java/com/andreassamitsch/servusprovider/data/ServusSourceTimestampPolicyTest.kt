package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ServusSourceTimestampPolicyTest {
    @Test
    fun availabilityTimestampWinsOverBroadcastStart() {
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
    fun broadcastStartIsUsedWhenAvailabilityTimestampIsMissing() {
        val card = ServusCardDto(
            id = "episode",
            startTime = "2026-09-01T08:00:00Z",
        )

        assertEquals(
            Instant.parse("2026-09-01T08:00:00Z").toEpochMilli(),
            ServusSourceTimestampPolicy.resolve(card, Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun visibleEditorialDateAndTimeRemainAValidFallback() {
        val now = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()
        val card = ServusCardDto(
            id = "episode",
            title = "Nachrichten 19:20 | 31.08.",
        )
        val expected = LocalDate.of(2026, 8, 31)
            .atTime(19, 20)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, ServusSourceTimestampPolicy.resolve(card, now))
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
