package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.data.tmdb.TmdbEpisodeMetadata
import com.andreassamitsch.ilauncher.data.tmdb.TmdbMetadata
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextMediaMapperTest {
    @Test
    fun `maps Android episode type and preserves source playback data`() {
        val source = item(programType = 3)
        val media = WatchNextMediaMapper.base(source)

        assertEquals(MediaType.Episode, media.type)
        assertEquals("android_watch_next", media.source.provider)
        assertEquals("example.package:42", media.source.sourceId)
        assertEquals("intent://episode", media.source.intentUri)
        assertEquals("https://source/image.jpg", media.sourceArtworkUri)
        assertEquals(2_500L, media.playbackPositionMillis)
    }

    @Test
    fun `episode enrichment keeps source deep link and prefers episode metadata`() {
        val base = WatchNextMediaMapper.base(item(programType = 3))
        val enriched = WatchNextMediaMapper.enrich(
            base,
            TmdbMetadata(
                tmdbId = 100,
                mediaType = MediaType.Series,
                title = "Fallout",
                originalTitle = "Fallout",
                overview = "Serie",
                releaseYear = 2024,
                runtimeMinutes = 55,
                posterUri = "https://tmdb/poster.jpg",
                backdropUri = "https://tmdb/backdrop.jpg",
                logoUri = "https://tmdb/logo.png",
                voteAverage = 8.0,
                imdbId = "tt12637874",
                tvdbId = 416744,
                wikidataId = null,
                episode = TmdbEpisodeMetadata(
                    tmdbEpisodeId = 200,
                    seasonNumber = 2,
                    episodeNumber = 4,
                    title = "Episode Four",
                    overview = "Episode overview",
                    airYear = 2026,
                    runtimeMinutes = 52,
                    stillUri = "https://tmdb/still.jpg",
                    voteAverage = 8.4,
                ),
                confidence = 0.97f,
            ),
        )

        assertEquals(MediaType.Episode, enriched.type)
        assertEquals("https://tmdb/still.jpg", enriched.preferredArtworkUri)
        assertEquals("intent://episode", enriched.source.intentUri)
        assertEquals("Episode overview", enriched.overview)
        assertEquals("S2 · E4 · Episode Four", enriched.subtitle)
        assertEquals(100, enriched.tmdbId)
        assertEquals(200, enriched.tmdbEpisodeId)
    }

    private fun item(programType: Int?) = WatchNextItem(
        id = 42,
        sourceOrder = 0,
        packageName = "example.package",
        programType = programType,
        title = "Fallout",
        seasonDisplayNumber = "2",
        episodeDisplayNumber = "4",
        episodeTitle = "Source Episode",
        shortDescription = "Source overview",
        posterArtUri = "https://source/poster.jpg",
        thumbnailUri = "https://source/image.jpg",
        logoUri = null,
        intentUri = "intent://episode",
        durationMillis = 3_000L,
        playbackPositionMillis = 2_500L,
        watchNextType = null,
        lastEngagementTimeUtcMillis = 123L,
    )
}
