package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbPersonWorksPolicyTest {
    @Test
    fun nonNarrativeTvAndSelfAppearancesAreFilteredButMoviesRemain() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 1,
                    mediaType = "tv",
                    name = "Jimmy Kimmel Live!",
                    genreIds = listOf(10767),
                    character = "Self - Guest",
                    popularity = 900.0,
                    voteCount = 500,
                ),
                TmdbPersonCreditDto(
                    id = 2,
                    mediaType = "tv",
                    name = "Reality Show",
                    genreIds = listOf(10764),
                    character = "Self",
                ),
                TmdbPersonCreditDto(
                    id = 3,
                    mediaType = "tv",
                    name = "Drama Series",
                    genreIds = listOf(18),
                    character = "Detective",
                    popularity = 40.0,
                    voteCount = 2_000,
                ),
                TmdbPersonCreditDto(
                    id = 4,
                    mediaType = "tv",
                    name = "Comedy Special",
                    genreIds = listOf(35),
                    character = "Self",
                ),
                TmdbPersonCreditDto(
                    id = 5,
                    mediaType = "movie",
                    title = "Documentary Film",
                    character = "Self",
                    popularity = 10.0,
                    voteCount = 300,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(setOf(3, 5), result.map { it.id }.toSet())
    }

    @Test
    fun establishedWorkRanksAheadOfShortPopularitySpike() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 10,
                    mediaType = "tv",
                    name = "Established Series",
                    genreIds = listOf(18),
                    character = "Lead",
                    popularity = 35.0,
                    voteAverage = 8.2,
                    voteCount = 12_000,
                ),
                TmdbPersonCreditDto(
                    id = 20,
                    mediaType = "tv",
                    name = "Current Spike",
                    genreIds = listOf(18),
                    character = "Lead",
                    popularity = 900.0,
                    voteAverage = 8.8,
                    voteCount = 70,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(listOf(10, 20), result.map { it.id })
    }

    @Test
    fun knownDepartmentKeepsActingAndDirectingFilmographiesRelevant() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 100,
                    mediaType = "movie",
                    title = "Acting Credit",
                    popularity = 20.0,
                    voteCount = 500,
                ),
            ),
            crew = listOf(
                TmdbPersonCreditDto(
                    id = 200,
                    mediaType = "movie",
                    title = "Producer Credit",
                    job = "Producer",
                    popularity = 500.0,
                    voteCount = 10_000,
                ),
                TmdbPersonCreditDto(
                    id = 300,
                    mediaType = "movie",
                    title = "Directed Film",
                    job = "Director",
                    popularity = 15.0,
                    voteCount = 400,
                ),
            ),
        )

        assertEquals(listOf(100), credits.rankedRelevantPersonCredits("Acting").map { it.id })
        assertEquals(listOf(300), credits.rankedRelevantPersonCredits("Directing").map { it.id })
    }
}
