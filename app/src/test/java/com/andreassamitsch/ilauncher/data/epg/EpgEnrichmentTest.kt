package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.data.tmdb.TmdbMetadata
import com.andreassamitsch.ilauncher.data.tmdb.TmdbEpisodeMetadata
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgEnrichmentTest {
    private val airing = LiveTvProgram(
        eventId = 12, title = "Serie", startUtcMillis = 1000, durationMillis = 3600000,
        shortDescription = "Konkrete Folge", longDescription = "Konkrete Handlung dieser Folge",
        subtitle = "EPG-Folgentitel", seasonNumber = 2, episodeNumber = 3,
        releaseYear = 2024, imageUri = "epg.jpg",
    )
    private val metadata = TmdbMetadata(
        tmdbId = 123, mediaType = MediaType.Series, title = "Serie", originalTitle = null,
        overview = "Allgemeine Serienbeschreibung", releaseYear = 2010, runtimeMinutes = null,
        posterUri = "poster.jpg", backdropUri = "series.jpg", logoUri = null, voteAverage = 8.0,
        imdbId = null, tvdbId = null, wikidataId = null, confidence = 0.95f,
    )

    @Test fun genericSeriesAddsIdentityButKeepsAiring() {
        val result = airing.withTmdbEnrichment(metadata)
        assertEquals(airing.longDescription, result.longDescription)
        assertEquals(airing.shortDescription, result.shortDescription)
        assertEquals(airing.releaseYear, result.releaseYear)
        assertEquals(airing.subtitle, result.subtitle)
        assertEquals(airing.imageUri, result.preferredArtworkUri)
        assertEquals(123, result.tmdbId)
        assertNull(result.tmdbOverview)
        assertNull(airing.copy(longDescription = null, shortDescription = null)
            .withTmdbEnrichment(metadata).longDescription)
    }

    @Test fun exactEpisodeFillsMissingFieldsOnly() {
        val episode = TmdbEpisodeMetadata(
            tmdbEpisodeId = 99, seasonNumber = 2, episodeNumber = 3, title = "TMDB Folge",
            overview = "Exakte Handlung", airYear = 2024, runtimeMinutes = 45,
            stillUri = "still.jpg", voteAverage = 7.0,
        )
        val result = airing.withTmdbEnrichment(metadata.copy(episode = episode))
        assertEquals(airing.longDescription, result.longDescription)
        assertEquals("still.jpg", result.preferredArtworkUri)
        val empty = airing.copy(shortDescription = null, longDescription = null, subtitle = null)
            .withTmdbEnrichment(metadata.copy(episode = episode))
        assertEquals("Exakte Handlung", empty.longDescription)
        assertEquals("TMDB Folge", empty.subtitle)
        val wrong = airing.withTmdbEnrichment(metadata.copy(episode = episode.copy(episodeNumber = 4)))
        assertNull(wrong.tmdbEpisodeId)
        assertEquals("epg.jpg", wrong.preferredArtworkUri)
    }

    @Test fun movieFillsMissingTextAndUsesSpecificBackdrop() {
        val movie = airing.copy(seasonNumber = null, episodeNumber = null,
            longDescription = null, shortDescription = null)
        val result = movie.withTmdbEnrichment(metadata.copy(mediaType = MediaType.Movie, overview = "Filmhandlung"))
        assertEquals("Filmhandlung", result.longDescription)
        assertEquals("series.jpg", result.preferredArtworkUri)
    }

    @Test fun refreshRetainsEnrichmentButAcceptsNewEpgFields() {
        val old = airing.withTmdbEnrichment(metadata)
        val fresh = airing.copy(longDescription = "Aktualisierte EPG-Handlung", durationMillis = 4000000)
        val result = fresh.retainEpgEnrichment(old)
        assertEquals(123, result.tmdbId)
        assertEquals(fresh.longDescription, result.longDescription)
        assertEquals(fresh.durationMillis, result.durationMillis)
        assertEquals("series.jpg", result.backdropUri)
        assertNull(fresh.copy(episodeNumber = 4).retainEpgEnrichment(old).tmdbId)
        assertNull(fresh.copy(startUtcMillis = 2000).retainEpgEnrichment(old).tmdbId)
        assertNull(fresh.copy(title = "Andere Sendung").retainEpgEnrichment(old).tmdbId)
    }
}
