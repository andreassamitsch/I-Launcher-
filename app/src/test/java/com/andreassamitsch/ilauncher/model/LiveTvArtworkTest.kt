package com.andreassamitsch.ilauncher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvArtworkTest {
    private val programme = LiveTvProgram(
        eventId = 1L, title = "Serie", startUtcMillis = 1000L, durationMillis = 3600000L,
        imageUri = "epg", tmdbId = 12, tmdbType = MediaType.Series, backdropUri = "wide",
        posterUri = "poster",
    )

    @Test fun matchedSeriesAndFilmUseWideArtwork() {
        assertEquals(LiveTvArtwork("wide", LiveTvArtworkSource.TmdbSeries), programme.selectArtwork())
        assertEquals(LiveTvArtwork("wide", LiveTvArtworkSource.TmdbFilm),
            programme.copy(tmdbType = MediaType.Movie).selectArtwork(forHero = true))
        val episode = programme.copy(episodeStillUri = "still", alternateBackdropUri = "fanart")
        assertEquals("wide", episode.selectArtwork()?.uri)
        assertEquals("still", episode.selectArtwork(forHero = true)?.uri)
        assertEquals(listOf("wide", "still", "fanart", "epg", "poster"), episode.artworkCandidates().map { it.uri })
        assertEquals("fanart", episode.copy(episodeStillUri = null).selectArtwork(forHero = true)?.uri)
        assertNull(episode.copy(startUtcMillis = 0).selectArtwork())
    }

    @Test fun factualCategoriesKeepSourceArtwork() {
        for (category in listOf("News", "Sport", "Nachrichten", "Magazin", "Current affairs", "Reportage")) {
            assertEquals(LiveTvArtwork("epg", LiveTvArtworkSource.Epg),
                programme.copy(categories = listOf(category)).selectArtwork(forHero = true))
        }
        assertEquals("wide", programme.copy(categories = listOf("Crime drama")).preferredArtworkUri)
    }

    @Test fun unresolvedOrMissingBackdropFallsBackToEpg() {
        assertEquals("epg", programme.copy(tmdbId = null).preferredArtworkUri)
        assertEquals("epg", programme.copy(backdropUri = " ").preferredArtworkUri)
        assertEquals("epg", programme.copy(tmdbType = MediaType.Unknown).preferredArtworkUri)
    }

    @Test fun heroNeverUsesPoster() {
        val p = programme.copy(imageUri = null, backdropUri = null)
        assertEquals("poster", p.preferredArtworkUri)
        assertNull(p.selectArtwork(forHero = true))
    }
}
