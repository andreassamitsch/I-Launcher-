package com.andreassamitsch.ilauncher.data.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextMapperTest {
    @Test
    fun `preserves the order requested from TvProvider exactly`() {
        val rows = listOf(
            row(id = 12, title = "Neueste Interaktion"),
            row(id = 55, title = "Mittlere Interaktion"),
            row(id = 91, title = "Älteste Interaktion"),
        )

        val mapped = WatchNextMapper.map(rows)

        assertEquals(listOf(12L, 55L, 91L), mapped.map { it.id })
        assertEquals(listOf(0, 1, 2), mapped.map { it.sourceOrder })
    }

    @Test
    fun `requests last engagement descending from TvProvider`() {
        assertEquals(
            "last_engagement_time_utc_millis DESC",
            WatchNextRepository.SORT_ORDER,
        )
    }

    @Test
    fun `preserves Android program type`() {
        val mapped = WatchNextMapper.map(listOf(row(id = 1, programType = 3)))

        assertEquals(3, mapped.single().programType)
    }

    @Test
    fun `computes progress only when duration and position are usable`() {
        val mapped = WatchNextMapper.map(
            listOf(
                row(id = 1, durationMillis = 10_000, playbackPositionMillis = 2_500),
                row(id = 2, durationMillis = 0, playbackPositionMillis = 2_500),
            ),
        )

        assertEquals(0.25f, mapped[0].progressFraction)
        assertNull(mapped[1].progressFraction)
    }

    private fun row(
        id: Long,
        programType: Int? = null,
        title: String? = null,
        durationMillis: Long? = null,
        playbackPositionMillis: Long? = null,
    ) = WatchNextRawRow(
        id = id,
        packageName = "example.package",
        programType = programType,
        title = title,
        seasonDisplayNumber = null,
        episodeDisplayNumber = null,
        episodeTitle = null,
        shortDescription = null,
        posterArtUri = null,
        thumbnailUri = null,
        logoUri = null,
        intentUri = null,
        durationMillis = durationMillis,
        playbackPositionMillis = playbackPositionMillis,
        watchNextType = null,
        lastEngagementTimeUtcMillis = null,
    )
}
