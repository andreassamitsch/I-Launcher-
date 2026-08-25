package com.andreassamitsch.ilauncher.ui.home

import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroArtworkTest {
    @Test
    fun tmdbMovieNeverFallsBackToPortraitPoster() {
        val item = mediaItem(
            type = MediaType.Movie,
            tmdbId = 1,
            posterUri = "poster",
            sourceArtworkUri = "source",
        )

        val (uri, fit) = mediaHeroArtwork(item)

        assertNull(uri)
        assertFalse(fit)
    }

    @Test
    fun tmdbEpisodePrefersStillThenBackdrop() {
        val item = mediaItem(
            type = MediaType.Episode,
            tmdbId = 1,
            episodeStillUri = "still",
            backdropUri = "backdrop",
            posterUri = "poster",
        )

        val (uri, fit) = mediaHeroArtwork(item)

        assertEquals("still", uri)
        assertFalse(fit)
    }

    @Test
    fun watchNextEpisodeCardCanPreferSeriesBackdrop() {
        val item = mediaItem(
            type = MediaType.Episode,
            tmdbId = 1,
            episodeStillUri = "still",
            backdropUri = "series-backdrop",
        )

        assertEquals(
            "series-backdrop",
            watchNextCardArtwork(item, WatchNextArtworkMode.Series),
        )
        assertEquals(
            "still",
            watchNextCardArtwork(item, WatchNextArtworkMode.Episode),
        )
    }

    @Test
    fun watchNextEpisodeHeroCanPreferSeriesBackdrop() {
        val item = mediaItem(
            type = MediaType.Episode,
            tmdbId = 1,
            episodeStillUri = "still",
            backdropUri = "series-backdrop",
        )

        assertEquals(
            "series-backdrop" to false,
            watchNextHeroArtwork(item, WatchNextArtworkMode.Series),
        )
        assertEquals(
            "still" to false,
            watchNextHeroArtwork(item, WatchNextArtworkMode.Episode),
        )
    }

    @Test
    fun nonTmdbSourceArtworkUsesRightFitPresentation() {
        val item = mediaItem(
            type = MediaType.Unknown,
            sourceArtworkUri = "source",
            posterUri = "poster",
        )

        val (uri, fit) = mediaHeroArtwork(item)

        assertEquals("source", uri)
        assertTrue(fit)
    }

    @Test
    fun liveTvWithoutTmdbUsesExistingImageRightAligned() {
        val program = LiveTvProgram(
            eventId = 1,
            title = "The Rookie",
            startUtcMillis = 1_000,
            durationMillis = 3_600_000,
            imageUri = "epg-image",
            posterUri = "poster",
        )

        val (uri, fit) = liveTvHeroArtwork(program)

        assertEquals("epg-image", uri)
        assertTrue(fit)
    }

    @Test
    fun tmdbLiveTvNeverFallsBackToPoster() {
        val program = LiveTvProgram(
            eventId = 1,
            title = "Movie",
            startUtcMillis = 1_000,
            durationMillis = 3_600_000,
            tmdbId = 10,
            tmdbType = MediaType.Movie,
            posterUri = "poster",
            imageUri = "epg-image",
        )

        val (uri, fit) = liveTvHeroArtwork(program)

        assertNull(uri)
        assertFalse(fit)
    }

    @Test
    fun liveTvHeroPrefersTmdbTitleLogoAndKeepsPiconAsSourceIdentity() {
        val hero = liveTvHero(
            LiveTvChannel(
                serviceReference = "1:0:1:test",
                name = "ORF 1",
                piconUri = "picon",
                now = LiveTvProgram(
                    eventId = 1,
                    title = "The Rookie",
                    startUtcMillis = 1_000,
                    durationMillis = 3_600_000,
                    tmdbId = 10,
                    tmdbType = MediaType.Series,
                    tmdbLogoUri = "tmdb-title-logo",
                ),
            ),
        )

        assertEquals("tmdb-title-logo", hero.logoUri)
        assertEquals("picon", hero.eyebrowLogoUri)
        assertTrue(hero.titleCoveredByLogo)
        assertEquals("ORF 1", hero.eyebrow)
    }

    @Test
    fun liveTvHeroKeepsExistingPiconFallbackWithoutTmdbTitleLogo() {
        val hero = liveTvHero(
            LiveTvChannel(
                serviceReference = "1:0:1:test",
                name = "ORF 1",
                piconUri = "picon",
                now = LiveTvProgram(
                    eventId = 1,
                    title = "ZiB",
                    startUtcMillis = 1_000,
                    durationMillis = 3_600_000,
                ),
            ),
        )

        assertEquals("picon", hero.logoUri)
        assertNull(hero.eyebrowLogoUri)
        assertFalse(hero.titleCoveredByLogo)
    }

    private fun mediaItem(
        type: MediaType,
        tmdbId: Int? = null,
        posterUri: String? = null,
        backdropUri: String? = null,
        episodeStillUri: String? = null,
        sourceArtworkUri: String? = null,
    ) = MediaItem(
        id = "id",
        type = type,
        title = "Title",
        tmdbId = tmdbId,
        posterUri = posterUri,
        backdropUri = backdropUri,
        episodeStillUri = episodeStillUri,
        sourceArtworkUri = sourceArtworkUri,
        source = MediaSource(provider = "test", sourceId = "source-id"),
    )
}
