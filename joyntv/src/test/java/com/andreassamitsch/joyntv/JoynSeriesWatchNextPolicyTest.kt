package com.andreassamitsch.joyntv

import org.junit.Assert.*
import org.junit.Test

class JoynSeriesWatchNextPolicyTest {
    private fun episode(n: Int, id: String = "at", season: Int = 23) = JoynMediaItem(
        id = "$id-$season-$n", title = "Episode $n", type = JoynMediaType.EPISODE,
        seriesId = id, seriesTitle = "Bauer sucht Frau", seasonNumber = season, episodeNumber = n,
    )
    private fun entry(n: Int, id: String = "at", season: Int = 23, time: Long = n.toLong()) =
        JoynContinueWatchingEntry("$id-$season-$n", episode(n, id, season), 300_000, 1_800_000, time)

    @Test fun olderUnfinishedEpisodeRemainsEligibleDespiteNewCatalogueEpisode() {
        val old = entry(3)
        assertEquals(old, JoynSeriesWatchNextPolicy.eligible(listOf(old)).single())
        assertTrue(JoynSeriesWatchNextPolicy.resumeWins(old.media, episode(11)))
    }
    @Test fun finishedResumeIsNotEligible() {
        assertTrue(JoynSeriesWatchNextPolicy.eligible(listOf(entry(3).copy(positionMs = 1_800_000))).isEmpty())
    }
    @Test fun latestEpisodeWinsEvenIfOldEpisodeWasTouchedLater() {
        val result = JoynSeriesWatchNextPolicy.eligible(listOf(entry(3, time = 99), entry(11)))
        assertEquals(11, result.single().media.episodeNumber)
    }
    @Test fun latestSeasonWins() {
        assertEquals(24, JoynSeriesWatchNextPolicy.eligible(listOf(entry(12), entry(1, season = 24))).single().media.seasonNumber)
    }
    @Test fun sameEpisodeResumeWinsOverAnnouncement() {
        assertTrue(JoynSeriesWatchNextPolicy.resumeWins(episode(11), episode(11)))
        assertTrue(JoynSeriesWatchNextPolicy.resumeWins(episode(3), episode(11)))
        assertFalse(JoynSeriesWatchNextPolicy.resumeWins(episode(3, "de"), episode(11)))
    }
    @Test fun distinctMarketsWithSameTitleRemainSeparate() {
        assertEquals(2, JoynSeriesWatchNextPolicy.eligible(listOf(entry(3), entry(3, "de"))).size)
    }
}
