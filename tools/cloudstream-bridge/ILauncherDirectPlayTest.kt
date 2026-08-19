package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ILauncherDirectPlayTest {
    @Test
    fun parsesMovieIdentity() {
        val request = ILauncherDirectPlay.parseRequest(
            "cloudstreamplay://v1?title=Dune%3A%20Part%20Two&type=movie&year=2024&tmdbId=693134&imdbId=tt15239678",
        )
        requireNotNull(request)
        assertEquals("Dune: Part Two", request.title)
        assertEquals(ILauncherDirectPlay.MediaKind.Movie, request.kind)
        assertEquals(2024, request.year)
        assertEquals(693134, request.tmdbId)
        assertEquals("tt15239678", request.imdbId)
        assertEquals(ILauncherDirectPlay.ProviderSelection.Automatic, request.providerSelection)
        assertNull(request.season)
        assertNull(request.episode)
    }

    @Test
    fun parsesExactEpisodeIdentityUnicodeAndProviderChooser() {
        val request = ILauncherDirectPlay.parseRequest(
            "cloudstreamplay://v1?title=Die%20drei%20%3F%3F%3F&type=episode&season=2&episode=4&tmdbId=123&tmdbEpisodeId=456&originalTitle=The%20Three%20Investigators&selection=choose",
        )
        requireNotNull(request)
        assertEquals("Die drei ???", request.title)
        assertEquals("The Three Investigators", request.originalTitle)
        assertEquals(ILauncherDirectPlay.MediaKind.Episode, request.kind)
        assertEquals(2, request.season)
        assertEquals(4, request.episode)
        assertEquals(123, request.tmdbId)
        assertEquals(456, request.tmdbEpisodeId)
        assertEquals(ILauncherDirectPlay.ProviderSelection.Choose, request.providerSelection)
    }

    @Test
    fun rejectsWrongVersionAndBlankTitle() {
        assertNull(ILauncherDirectPlay.parseRequest("cloudstreamplay://v2?title=Fallout&type=series"))
        assertNull(ILauncherDirectPlay.parseRequest("cloudstreamplay://v1?title=%20%20&type=series"))
    }

    @Test
    fun titleNormalizationIsStrictButPunctuationAgnostic() {
        assertEquals("dune part two", ILauncherDirectPlay.normalizeTitle("  Dune:   Part Two "))
        assertEquals("die drei", ILauncherDirectPlay.normalizeTitle("Die drei ???"))
    }

    @Test
    fun matchingNormalizationAcceptsProviderYearDecoration() {
        assertEquals(
            "dune part two",
            ILauncherDirectPlay.normalizeTitleForMatch("Dune: Part Two (2024)", 2024),
        )
        assertEquals(
            "dune part two 2023",
            ILauncherDirectPlay.normalizeTitleForMatch("Dune: Part Two (2023)", 2024),
        )
    }

    @Test
    fun providerCandidateRankingKeepsExactAndMatchingYearAheadOfWeakResults() {
        val request = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Dune%3A%20Part%20Two&type=movie&year=2024",
            ),
        )

        val exactRank = ILauncherDirectPlay.searchCandidateRank("Dune: Part Two", request)
        val decoratedRank = ILauncherDirectPlay.searchCandidateRank("Dune: Part Two (2024)", request)
        val weakRank = ILauncherDirectPlay.searchCandidateRank("Dune", request)

        assertEquals(0, exactRank)
        assertEquals(1, decoratedRank)
        assertTrue(weakRank > decoratedRank)
    }

    @Test
    fun providerPreferenceKeepsStoredOrderAndAppendsNewProviders() {
        assertEquals(
            listOf("CineZone", "StreamFlix", "SerienStream", "Neu"),
            ILauncherDirectPlay.mergeProviderOrder(
                activeNames = listOf("StreamFlix", "CineZone", "SerienStream", "Neu"),
                preferredOrder = listOf("CineZone", "StreamFlix", "SerienStream", "Nicht mehr installiert"),
            ),
        )
    }

    @Test
    fun lastSuccessfulProviderWinsBeforeGlobalPreference() {
        assertEquals(
            listOf("SerienStream", "CineZone", "StreamFlix"),
            ILauncherDirectPlay.prioritizeProviderNames(
                activeNames = listOf("StreamFlix", "CineZone", "SerienStream"),
                preferredOrder = listOf("CineZone", "StreamFlix", "SerienStream"),
                lastProvider = "SerienStream",
            ),
        )
    }

    @Test
    fun staleLastProviderIsIgnored() {
        assertEquals(
            listOf("CineZone", "StreamFlix"),
            ILauncherDirectPlay.prioritizeProviderNames(
                activeNames = listOf("StreamFlix", "CineZone"),
                preferredOrder = listOf("CineZone", "StreamFlix"),
                lastProvider = "Deinstalliert",
            ),
        )
    }

    @Test
    fun mediaIdentityUsesExactEpisodeWhenAvailable() {
        val episodeA = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Fallout&type=episode&tmdbId=106379&tmdbEpisodeId=1001&season=2&episode=4",
            ),
        )
        val episodeB = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Fallout&type=episode&tmdbId=106379&tmdbEpisodeId=1002&season=2&episode=5",
            ),
        )

        assertEquals("tmdbEpisode:1001", ILauncherDirectPlay.mediaIdentity(episodeA))
        assertNotEquals(ILauncherDirectPlay.mediaIdentity(episodeA), ILauncherDirectPlay.mediaIdentity(episodeB))
    }
}
