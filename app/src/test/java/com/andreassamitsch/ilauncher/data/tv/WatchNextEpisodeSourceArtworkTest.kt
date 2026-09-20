package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.data.tmdb.TmdbMetadata
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextEpisodeSourceArtworkTest {
    @Test fun episodeKeepsProviderStillWhenTmdbReturnsOnlyParentSeries() {
        val source = WatchNextItem(
            id = 42L, sourceOrder = 0, packageName = "com.andreassamitsch.joyntv",
            programType = 3, title = "Bauer sucht Frau", releaseDate = null,
            seasonDisplayNumber = null, episodeDisplayNumber = null,
            episodeTitle = "Bauer sucht Frau", shortDescription = null,
            posterArtUri = "https://example.com/episode.jpg", thumbnailUri = null,
            logoUri = null, intentUri = "intent://joyn-episode", durationMillis = null,
            playbackPositionMillis = null, watchNextType = null,
            lastEngagementTimeUtcMillis = null,
        )
        val base = WatchNextMediaMapper.base(source)
        val metadata = TmdbMetadata(
            tmdbId = 123, mediaType = MediaType.Series, title = "Bauer sucht Frau",
            originalTitle = "Bauer sucht Frau", overview = "Series overview",
            releaseYear = 2026, runtimeMinutes = null,
            posterUri = "https://example.com/series-poster.jpg",
            backdropUri = "https://example.com/series-backdrop.jpg", logoUri = null,
            voteAverage = null, imdbId = null, tvdbId = null, wikidataId = null,
            episode = null, confidence = 0.95f,
        )
        val enriched = WatchNextMediaMapper.enrich(base, metadata)
        assertEquals(MediaType.Episode, enriched.type)
        assertEquals("https://example.com/episode.jpg", enriched.preferredArtworkUri)
    }
}
