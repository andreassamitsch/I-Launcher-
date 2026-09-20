package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynResumeMediaMergerTest {
    private val full = JoynMediaItem(
        id = "episode-42", title = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
        videoId = "video-42", seasonNumber = 21, episodeNumber = 4,
        seriesTitle = "Bauer sucht Frau", seasonId = "season-21",
        imageUrl = "https://images.example/episode-42.jpg",
    )

    @Test fun accountResumeDoesNotEraseKnownEpisode() {
        val remote = full.copy(seasonNumber = null, episodeNumber = null, seasonId = null,
            imageUrl = "https://images.example/series.jpg")
        val merged = JoynResumeMediaMerger.merge(remote, full)
        assertEquals(21, merged.seasonNumber)
        assertEquals(4, merged.episodeNumber)
        assertEquals("season-21", merged.seasonId)
        assertEquals("https://images.example/episode-42.jpg", merged.imageUrl)
    }

    @Test fun anotherVideoNeverBorrowsEpisodeCoordinatesOrArtwork() {
        val remote = full.copy(id = "episode-43", videoId = "video-43", seasonNumber = null,
            episodeNumber = null, imageUrl = "https://images.example/episode-43.jpg")
        val merged = JoynResumeMediaMerger.merge(remote, full)
        assertNull(merged.seasonNumber)
        assertNull(merged.episodeNumber)
        assertEquals("https://images.example/episode-43.jpg", merged.imageUrl)
    }

    @Test fun authoritativeIncomingCoordinatesAreNotOverridden() {
        val updated = full.copy(seasonNumber = 22, episodeNumber = 1)
        val merged = JoynResumeMediaMerger.merge(updated, full)
        assertEquals(22, merged.seasonNumber)
        assertEquals(1, merged.episodeNumber)
    }
}
