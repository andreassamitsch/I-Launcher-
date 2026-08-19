package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbPersonWorksPolicyTest {
    @Test
    fun nonNarrativeAndGenericAppearancesAreFilteredAcrossMoviesAndTv() {
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
                    title = "Documentary Appearance",
                    character = "Self",
                    popularity = 10.0,
                    voteCount = 300,
                ),
                TmdbPersonCreditDto(
                    id = 6,
                    mediaType = "movie",
                    title = "Scripted Movie",
                    character = "Mara",
                    popularity = 18.0,
                    voteCount = 1_200,
                ),
                TmdbPersonCreditDto(
                    id = 7,
                    mediaType = "tv",
                    name = "Unclassified Interview",
                    genreIds = emptyList(),
                    character = "Interviewee",
                    popularity = 500.0,
                    voteCount = 100,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(setOf(3, 6), result.map { it.id }.toSet())
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
    fun broadlyKnownWorkRanksAheadOfHighlyRatedNicheTitle() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 30,
                    mediaType = "tv",
                    name = "Well Known Series",
                    genreIds = listOf(18),
                    character = "Lead",
                    popularity = 95.0,
                    voteAverage = 7.6,
                    voteCount = 5_500,
                ),
                TmdbPersonCreditDto(
                    id = 40,
                    mediaType = "movie",
                    title = "Niche Festival Film",
                    character = "Lead",
                    popularity = 4.0,
                    voteAverage = 9.4,
                    voteCount = 85,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(listOf(30, 40), result.map { it.id })
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
