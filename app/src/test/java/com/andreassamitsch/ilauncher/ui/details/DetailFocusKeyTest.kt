package com.andreassamitsch.ilauncher.ui.details

import com.andreassamitsch.ilauncher.data.tv.SeriesResumePosition
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SeriesSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailFocusKeyTest {
    @Test
    fun tmdbIdentityStaysStableAcrossMetadataRefresh() {
        val source = MediaSource(provider = "tmdb_related", sourceId = "source")
        val before = MediaItem(
            id = "old",
            type = MediaType.Movie,
            title = "Title",
            tmdbId = 123,
            source = source,
        )
        val after = before.copy(id = "new", title = "Localized title", overview = "Loaded later")

        assertEquals(detailMediaFocusKey(before), detailMediaFocusKey(after))
        assertEquals("Movie:123", detailMediaFocusKey(after))
    }

    @Test
    fun nonTmdbItemsFallBackToInternalId() {
        val item = MediaItem(
            id = "local-42",
            type = MediaType.Unknown,
            title = "Local",
            source = MediaSource(provider = "local", sourceId = "42"),
        )

        assertEquals("Unknown:local-42", detailMediaFocusKey(item))
    }

    @Test
    fun watchNextEpisodeUsesParentTmdbSeriesForCatalog() {
        val episode = watchNextEpisode()

        val series = detailSeriesContext(episode)

        assertEquals(MediaType.Series, series?.type)
        assertEquals(106379, series?.tmdbId)
        assertEquals("Fallout", series?.title)
        assertEquals(episode.source, series?.source)
    }

    @Test
    fun watchNextEpisodeProvidesExactResumePosition() {
        val resume = detailSeriesResume(watchNextEpisode())

        assertEquals(2, resume?.seasonNumber)
        assertEquals(4, resume?.episodeNumber)
        assertEquals("The Ghouls", resume?.episodeTitle)
        assertEquals(1_200_000L, resume?.playbackPositionMillis)
    }

    @Test
    fun runningSeasonWinsOverFirstRegularSeason() {
        val seasons = listOf(
            SeriesSeason(seasonNumber = 0, title = "Specials", episodeCount = 2),
            SeriesSeason(seasonNumber = 1, title = "Staffel 1", episodeCount = 8),
            SeriesSeason(seasonNumber = 2, title = "Staffel 2", episodeCount = 8),
        )

        assertEquals(
            2,
            preferredSeriesSeason(
                seasons,
                SeriesResumePosition(seasonNumber = 2, episodeNumber = 4),
            ),
        )
        assertEquals(1, preferredSeriesSeason(seasons, resume = null))
    }

    @Test
    fun movieDoesNotExposeSeriesCatalog() {
        val movie = watchNextEpisode().copy(type = MediaType.Movie)

        assertNull(detailSeriesContext(movie))
        assertNull(detailSeriesResume(movie))
    }

    private fun watchNextEpisode() = MediaItem(
        id = "watch-next:com.example:42",
        type = MediaType.Episode,
        title = "Fallout",
        subtitle = "S2 · E4 · The Ghouls",
        tmdbId = 106379,
        tmdbEpisodeId = 12345,
        seasonNumber = 2,
        episodeNumber = 4,
        episodeTitle = "The Ghouls",
        durationMillis = 2_400_000L,
        playbackPositionMillis = 1_200_000L,
        source = MediaSource(
            provider = "android_watch_next",
            sourceId = "com.example:42",
            packageName = "com.example",
        ),
    )
}
