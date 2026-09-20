package com.andreassamitsch.joyntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynAtEpisodeIdentityRegressionTest {
    @Test fun onlyIdenticalJoynVideoMayContributeEpisodeArtwork() {
        val entry = JoynContinueWatchingEntry(
            assetId = "at-asset",
            media = JoynMediaItem(
                id = "at-asset", title = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
                videoId = "at-video", seasonNumber = 23, episodeNumber = 3,
            ),
            positionMs = 180_000L, durationMs = 5_400_000L,
        )
        val de = JoynMediaItem(
            id = "de-asset", title = "Bauer sucht Frau", type = JoynMediaType.EPISODE,
            videoId = "de-video", seasonNumber = 23, episodeNumber = 3,
        )
        val at = de.copy(id = "at-asset", videoId = "at-video")
        assertFalse(JoynExactEpisodeMetadata.matches(entry, de))
        assertTrue(JoynExactEpisodeMetadata.matches(entry, at))
    }
}
