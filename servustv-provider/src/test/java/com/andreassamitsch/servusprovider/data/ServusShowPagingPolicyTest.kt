package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusShowPagingPolicyTest {
    @Test
    fun prefetchStartsOnlyNearEndOfLoadedEpisodes() {
        assertFalse(ServusShowPagingPolicy.shouldPrefetch(4, 12, hasMore = true))
        assertTrue(ServusShowPagingPolicy.shouldPrefetch(9, 12, hasMore = true))
        assertTrue(ServusShowPagingPolicy.shouldPrefetch(11, 12, hasMore = true))
        assertFalse(ServusShowPagingPolicy.shouldPrefetch(11, 12, hasMore = false))
    }

    @Test
    fun mergeKeepsMoreThanTvChannelLimitAndPrefersHydratedMetadata() {
        val cached = (1..24).map { index -> episode(index, description = null) }
        val hydrated = listOf(
            episode(1, description = "Ausführliche Episodenbeschreibung", season = 10, number = 240),
        )

        val merged = ServusShowPagingPolicy.mergeEpisodes(cached, hydrated)

        assertEquals(24, merged.size)
        val first = merged.first { it.id == "E1" }
        assertEquals("Ausführliche Episodenbeschreibung", first.description)
        assertEquals(10, first.seasonNumber)
        assertEquals(240, first.episodeNumber)
    }

    private fun episode(
        index: Int,
        description: String?,
        season: Int? = null,
        number: Int? = null,
    ) = ServusNewsEpisode(
        id = "E$index",
        title = "Folge $index",
        showName = "Testshow",
        description = description,
        durationMillis = 60_000L,
        publishedAtMillis = index * 60_000L,
        artworkUri = null,
        showId = "SHOW",
        contentType = "episode",
        seasonNumber = season,
        episodeNumber = number,
    )
}
