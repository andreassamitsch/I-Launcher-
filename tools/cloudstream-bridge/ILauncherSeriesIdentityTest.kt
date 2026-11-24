package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Test

class ILauncherSeriesIdentityTest {
    private fun candidate(index: Int, provider: String, imdb: String?, tmdb: String?, season: Int, episode: Int) =
        ILauncherSeriesIdentity.Candidate(index, provider, ILauncherSeriesIdentity.Identity(imdb, tmdb), season, episode)

    @Test fun preferLaterEpisodeAcrossVerifiedProviders() {
        assertEquals(setOf(1), ILauncherSeriesIdentity.winners(listOf(
            candidate(0, "a", "tt123", null, 1, 3),
            candidate(1, "b", "tt123", "11", 1, 7))))
    }

    @Test fun seasonWinsAndEqualEpisodeKeepsLatestEngagement() {
        assertEquals(setOf(1), ILauncherSeriesIdentity.winners(listOf(
            candidate(0, "a", null, "11", 1, 20),
            candidate(1, "b", null, "11", 2, 1),
            candidate(2, "c", null, "11", 2, 1))))
    }

    @Test fun conflictingMetadataNeverMerges() {
        assertEquals(setOf(0, 1), ILauncherSeriesIdentity.winners(listOf(
            candidate(0, "a", "tt123", "11", 1, 3),
            candidate(1, "b", "tt123", "22", 1, 7))))
    }

    @Test fun differentIdentityAndSameProviderStaySeparate() {
        assertEquals(setOf(0, 1, 2), ILauncherSeriesIdentity.winners(listOf(
            candidate(0, "a", "tt123", null, 1, 3),
            candidate(1, "b", "tt456", null, 1, 7),
            candidate(2, "a", "tt123", null, 1, 8))))
    }
}
