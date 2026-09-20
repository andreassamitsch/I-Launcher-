package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynResumeMediaMergerIdentityTest {
    @Test fun sameVideoRetainsKnownEpisodeWithoutBorrowingSeriesArtwork() {
        val known = JoynMediaItem(
            id = "asset", title = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
            videoId = "video", seriesTitle = "Bauer sucht Frau", seasonNumber = 21,
            episodeNumber = 4, imageUrl = "https://example.com/episode.jpg",
        )
        val lightweight = known.copy(
            seasonNumber = null, episodeNumber = null,
            imageUrl = "https://example.com/series.jpg",
        )
        val merged = JoynResumeMediaMerger.merge(lightweight, known)
        assertEquals(21, merged.seasonNumber)
        assertEquals(4, merged.episodeNumber)
        assertEquals("https://example.com/episode.jpg", merged.imageUrl)
    }

    @Test fun differentVideoKeepsItsOwnMetadata() {
        val previous = JoynMediaItem(
            id = "episode", title = "Serie", type = JoynMediaType.EPISODE,
            videoId = "first-video", seasonNumber = 1, episodeNumber = 2,
        )
        val nextVideo = previous.copy(videoId = "other-video", seasonNumber = null, episodeNumber = null)
        val merged = JoynResumeMediaMerger.merge(nextVideo, previous)
        assertNull(merged.seasonNumber)
        assertNull(merged.episodeNumber)
    }
}
