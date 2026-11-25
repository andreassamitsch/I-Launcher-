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

    private fun publication(provider: String, parent: Int, imdb: String?, tmdb: String?, season: Int, episode: Int, time: Long = 0) =
        ILauncherSeriesIdentity.Publication(provider, parent,
            if (imdb == null && tmdb == null) null else ILauncherSeriesIdentity.Identity(imdb, tmdb),
            season, episode, time)

    @Test fun activeResumeSuppressesVerifiedAnnouncementAcrossProviders() {
        val resume = publication("a", 1, "tt123", "11", 4, 5)
        val next = publication("b", 2, "tt123", null, 4, 6)
        assertEquals(emptySet<Int>(), ILauncherSeriesIdentity.announcementWinners(listOf(resume), listOf(next)))
    }

    @Test fun announcementsSelectOneVerifiedSuccessorAndPreserveUnknown() {
        val first = publication("a", 1, null, "11", 4, 5)
        val later = publication("b", 2, null, "11", 4, 6)
        val unknown = publication("c", 3, null, null, 4, 6)
        assertEquals(setOf(1, 2), ILauncherSeriesIdentity.announcementWinners(emptyList(), listOf(first, later, unknown)))
    }

    @Test fun conflictsAndUnknownProvidersAreNotMerged() {
        val resume = publication("a", 1, "tt123", "11", 4, 5)
        val conflict = publication("b", 2, "tt123", "22", 4, 6)
        val unknown = publication("c", 3, null, null, 4, 6)
        assertEquals(setOf(0, 1), ILauncherSeriesIdentity.announcementWinners(listOf(resume), listOf(conflict, unknown)))
    }

    @Test fun sameProviderParentSuppressesAnnouncementEvenWithoutExternalIds() {
        val resume = publication("a", 1, null, null, 4, 5)
        val next = publication("a", 1, null, null, 4, 6)
        assertEquals(emptySet<Int>(), ILauncherSeriesIdentity.announcementWinners(listOf(resume), listOf(next)))
    }

    @Test fun equalAnnouncementUsesLatestCompletion() {
        val first = publication("a", 1, null, "11", 4, 5, 10)
        val second = publication("b", 2, null, "11", 4, 5, 20)
        assertEquals(setOf(1), ILauncherSeriesIdentity.announcementWinners(emptyList(), listOf(first, second)))
    }

    @Test fun metadataMatchRequiresExactNormalizedTitleAndKnownYear() {
        assertEquals(true, ILauncherSeriesIdentity.sameTitle("Ted Lasso", "Ted Lasso", null))
        assertEquals(false, ILauncherSeriesIdentity.sameTitle("Ted Lasso", "Ted Lasso Returns", null))
        assertEquals(true, ILauncherSeriesIdentity.yearMatch(2020, 2020))
        assertEquals(false, ILauncherSeriesIdentity.yearMatch(null, 2020))
        assertEquals(false, ILauncherSeriesIdentity.yearMatch(2020, 2021))
    }

    @Test fun genericEpisodeNamesAreNotEvidence() {
        assertEquals(null, ILauncherSeriesIdentity.meaningfulEpisodeName("Episode 1"))
        assertEquals(null, ILauncherSeriesIdentity.meaningfulEpisodeName("Folge 12"))
        assertEquals("the diamond dogs", ILauncherSeriesIdentity.meaningfulEpisodeName("The Diamond Dogs"))
    }

    @Test fun differentIdentityAndSameProviderStaySeparate() {
        assertEquals(setOf(0, 1, 2), ILauncherSeriesIdentity.winners(listOf(
            candidate(0, "a", "tt123", null, 1, 3),
            candidate(1, "b", "tt456", null, 1, 7),
            candidate(2, "a", "tt123", null, 1, 8))))
    }
}
