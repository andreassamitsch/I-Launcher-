package com.andreassamitsch.joyntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynNextEpisodeAnnouncementTest {
    private val known = JoynCompletedSeries(
        seriesKey = "at:bauersuchtfrau",
        seriesTitle = "Bauer sucht Frau",
        seriesId = "austrian-show",
        seriesPath = "/at/bauer-sucht-frau",
        seasonId = "season-23",
        seasonNumber = 23,
        episodeNumber = 3,
        completedAt = 1000L,
    )

    @Test fun alreadyAvailableFollowingEpisodeUsesNext() {
        assertFalse(JoynNextEpisodePolicy.isNewlyAvailable(known))
    }

    @Test fun episodeAppearingAfterPreviouslyAbsentUsesNew() {
        assertTrue(JoynNextEpisodePolicy.isNewlyAvailable(known.copy(observedWithoutNext = true)))
    }

    @Test fun previouslyAnnouncedEpisodeCannotBeReclassifiedNew() {
        assertFalse(JoynNextEpisodePolicy.isNewlyAvailable(
            known.copy(observedWithoutNext = true, announcedVideoId = "video-episode-4"),
        ))
    }
}
