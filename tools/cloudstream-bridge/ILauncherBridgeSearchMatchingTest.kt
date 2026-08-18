package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ILauncherBridgeSearchMatchingTest {
    @Test
    fun broadOrProviderGetsHighSignalFallbackQuery() {
        val request = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Der%20Super%20Mario%20Galaxy%20Film&type=movie&year=2026",
            ),
        )

        assertEquals(
            listOf("Der Super Mario Galaxy Film", "super mario galaxy"),
            ILauncherDirectPlay.buildSearchQueries(request),
        )
    }

    @Test
    fun broadWordHitsRankBehindFullTitleEvenWhenProviderDecoratesYear() {
        val request = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Der%20Super%20Mario%20Galaxy%20Film&type=movie&year=2026",
            ),
        )
        val candidates = listOf(
            "Der Pate",
            "Superman",
            "Mario Barth: Männer sind faul",
            "Galaxy Quest",
            "Ein Film über Filme",
            "Der Super Mario Galaxy Film (2026)",
        )

        assertEquals(
            "Der Super Mario Galaxy Film (2026)",
            candidates.sortedBy { ILauncherDirectPlay.searchCandidateRank(it, request) }.first(),
        )
    }

    @Test
    fun exactMovieSearchHitSurvivesWrongTvSeriesSearchResponseType() {
        val request = requireNotNull(
            ILauncherDirectPlay.parseRequest(
                "cloudstreamplay://v1?title=Matrix%20Revolutions&type=movie&year=2003",
            ),
        )

        val exactButMislabeled = ILauncherDirectPlay.searchCandidateSortKey(
            "Matrix Revolutions",
            TvType.TvSeries,
            request,
        )
        val weakButCorrectlyTyped = ILauncherDirectPlay.searchCandidateSortKey(
            "The Matrix",
            TvType.Movie,
            request,
        )

        assertTrue(exactButMislabeled < weakButCorrectlyTyped)
        assertEquals(0, ILauncherDirectPlay.searchCandidateRank("Matrix Revolutions", request))
    }

    @Test
    fun explicitProviderYearDecorationMatchesEvenWithoutSourceYear() {
        assertEquals(
            "dune part two",
            ILauncherDirectPlay.normalizeTitleForMatch("Dune: Part Two (2024)", null),
        )
        assertEquals(
            "dune part two",
            ILauncherDirectPlay.normalizeTitleForMatch("Dune: Part Two [2024]", null),
        )
    }

    @Test
    fun conflictingDecoratedYearRemainsPartOfStrictMatch() {
        assertEquals(
            "dune part two 2023",
            ILauncherDirectPlay.normalizeTitleForMatch("Dune: Part Two (2023)", 2024),
        )
    }

    @Test
    fun numericTitleIsNotMistakenForProviderYearDecoration() {
        assertEquals(
            "blade runner 2049",
            ILauncherDirectPlay.normalizeTitleForMatch("Blade Runner 2049", null),
        )
    }

    @Test
    fun broadPagedSearchContinuesOnlyUntilSafeTitleCandidateExists() {
        assertTrue(ILauncherDirectPlay.shouldFetchNextSearchPage(hasNext = true, page = 1, bestRank = 450))
        assertTrue(ILauncherDirectPlay.shouldFetchNextSearchPage(hasNext = true, page = 2, bestRank = null))
        assertFalse(ILauncherDirectPlay.shouldFetchNextSearchPage(hasNext = true, page = 1, bestRank = 1))
        assertFalse(ILauncherDirectPlay.shouldFetchNextSearchPage(hasNext = true, page = 3, bestRank = 500))
        assertFalse(ILauncherDirectPlay.shouldFetchNextSearchPage(hasNext = false, page = 1, bestRank = 500))
    }
}
