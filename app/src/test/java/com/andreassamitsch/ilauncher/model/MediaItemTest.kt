package com.andreassamitsch.ilauncher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaItemTest {
    @Test
    fun `episode prefers still over other artwork`() {
        val media = MediaItem(
            id = "episode",
            type = MediaType.Episode,
            title = "Episode",
            posterUri = "poster",
            backdropUri = "backdrop",
            episodeStillUri = "still",
            sourceArtworkUri = "source",
            source = MediaSource(provider = "test", sourceId = "1"),
        )

        assertEquals("still", media.preferredArtworkUri)
    }

    @Test
    fun `movie falls back from backdrop to poster to source`() {
        val media = MediaItem(
            id = "movie",
            type = MediaType.Movie,
            title = "Movie",
            posterUri = "poster",
            sourceArtworkUri = "source",
            source = MediaSource(provider = "test", sourceId = "2"),
        )

        assertEquals("poster", media.preferredArtworkUri)
    }
}
