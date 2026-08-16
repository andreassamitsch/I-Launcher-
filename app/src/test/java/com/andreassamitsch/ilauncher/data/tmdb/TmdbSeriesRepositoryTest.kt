package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbSeriesRepositoryTest {
    private val images = TmdbImageConfiguration(
        secureBaseUrl = "https://image.tmdb.org/t/p/",
        posterSize = "w780",
        backdropSize = "w1280",
        logoSize = "w500",
        stillSize = "w780",
        updatedAtUtcMillis = 1L,
    )

    @Test
    fun `season summaries keep specials and regular seasons in TMDB order`() {
        val seasons = TmdbMediaDetailsDto(
            id = 10,
            seasons = listOf(
                TmdbSeasonSummaryDto(seasonNumber = 2, name = "Staffel 2", episodeCount = 8),
                TmdbSeasonSummaryDto(seasonNumber = 0, name = "Specials", episodeCount = 2),
                TmdbSeasonSummaryDto(seasonNumber = 1, name = "Staffel 1", episodeCount = 8),
                TmdbSeasonSummaryDto(seasonNumber = 3, name = "Leer", episodeCount = 0),
            ),
        ).toSeriesSeasons(images)

        assertEquals(listOf(0, 1, 2), seasons.map { it.seasonNumber })
    }

    @Test
    fun `season episode mapping preserves series identity for CloudStream direct play`() {
        val series = MediaItem(
            id = "tmdb:Series:10",
            type = MediaType.Series,
            title = "Testserie",
            originalTitle = "Test Series",
            tmdbId = 10,
            imdbId = "tt123",
            source = MediaSource(provider = "tmdb", sourceId = "10"),
        )
        val content = TmdbSeasonDetailsDto(
            id = 100,
            name = "Staffel 2",
            seasonNumber = 2,
            episodes = listOf(
                TmdbSeasonEpisodeDto(
                    id = 205,
                    name = "Fünfte Folge",
                    episodeNumber = 5,
                    seasonNumber = 2,
                    stillPath = "/still.jpg",
                    runtime = 47,
                ),
            ),
        ).toSeriesSeasonContent(series, images)

        val episode = content.episodes.single()
        assertEquals(MediaType.Episode, episode.type)
        assertEquals("Testserie", episode.title)
        assertEquals("Fünfte Folge", episode.episodeTitle)
        assertEquals(2, episode.seasonNumber)
        assertEquals(5, episode.episodeNumber)
        assertEquals(205, episode.tmdbEpisodeId)
        assertEquals("tt123", episode.imdbId)
        assertTrue(episode.episodeStillUri?.endsWith("/w780/still.jpg") == true)
    }
}
