package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesResumeRepositoryTest {
    private val series = MediaItem(
        id = "tmdb:Series:106379",
        type = MediaType.Series,
        title = "Fallout",
        originalTitle = "Fallout",
        releaseYear = 2024,
        tmdbId = 106379,
        imdbId = "tt12637874",
        source = MediaSource(provider = "tmdb", sourceId = "106379"),
    )

    @Test
    fun `series defaults to season one episode one without Watch Next resume`() {
        val target = seriesPlaybackTarget(series, resume = null)

        assertEquals(MediaType.Episode, target.type)
        assertEquals(1, target.seasonNumber)
        assertEquals(1, target.episodeNumber)
        assertEquals(106379, target.tmdbId)
        assertEquals("Fallout", target.title)
    }

    @Test
    fun `matching Watch Next row becomes direct resume episode`() {
        val resume = resolveSeriesResume(
            series,
            listOf(
                watchNext(
                    packageName = "com.lagradost.cloudstream3.prerelease.debug",
                    title = "Fallout",
                    season = "2",
                    episode = "4",
                    episodeTitle = "The Ghouls",
                    position = 1_200_000L,
                ),
            ),
        )
        val target = seriesPlaybackTarget(series, resume)

        assertEquals(2, target.seasonNumber)
        assertEquals(4, target.episodeNumber)
        assertEquals("The Ghouls", target.episodeTitle)
        assertEquals(1_200_000L, target.playbackPositionMillis)
        assertEquals(MediaType.Episode, target.type)
    }

    @Test
    fun `non CloudStream Watch Next also resumes series`() {
        val resume = resolveSeriesResume(
            series,
            listOf(watchNext(packageName = "com.netflix.ninja", title = "Fallout", season = "2", episode = "4")),
        )

        assertEquals(2, resume?.seasonNumber)
        assertEquals(4, resume?.episodeNumber)
    }

    @Test
    fun `unrelated newer row is skipped without reordering matching rows`() {
        val resume = resolveSeriesResume(
            series,
            listOf(
                watchNext(packageName = "com.netflix.ninja", title = "Shogun", season = "1", episode = "8"),
                watchNext(packageName = "com.lagradost.cloudstream3", title = "Fallout", season = "1", episode = "6"),
                watchNext(packageName = "com.netflix.ninja", title = "Fallout", season = "1", episode = "2"),
            ),
        )

        assertEquals(1, resume?.seasonNumber)
        assertEquals(6, resume?.episodeNumber)
    }

    private fun watchNext(
        packageName: String,
        title: String,
        season: String,
        episode: String,
        episodeTitle: String? = null,
        position: Long? = null,
    ) = WatchNextItem(
        id = 1L,
        sourceOrder = 0,
        packageName = packageName,
        programType = null,
        title = title,
        releaseDate = null,
        seasonDisplayNumber = season,
        episodeDisplayNumber = episode,
        episodeTitle = episodeTitle,
        shortDescription = null,
        posterArtUri = null,
        thumbnailUri = null,
        logoUri = null,
        intentUri = null,
        durationMillis = 2_400_000L,
        playbackPositionMillis = position,
        watchNextType = null,
        lastEngagementTimeUtcMillis = 1000L,
    )
}
