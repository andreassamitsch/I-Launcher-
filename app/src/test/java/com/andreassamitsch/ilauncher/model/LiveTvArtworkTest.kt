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
        assertEquals(LiveTvArtwork("still", LiveTvArtworkSource.TmdbEpisode),
            programme.copy(episodeStillUri = "still").selectArtwork())
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
