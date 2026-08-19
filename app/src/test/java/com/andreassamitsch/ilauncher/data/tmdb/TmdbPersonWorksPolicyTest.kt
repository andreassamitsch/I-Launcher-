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
    fun signatureMovieRanksAheadOfSingleEpisodeHitShows() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 50,
                    mediaType = "tv",
                    name = "The Simpsons",
                    genreIds = listOf(16, 35),
                    character = "Guest Character",
                    episodeCount = 1,
                    popularity = 500.0,
                    voteAverage = 8.0,
                    voteCount = 10_000,
                ),
                TmdbPersonCreditDto(
                    id = 60,
                    mediaType = "tv",
                    name = "Friends",
                    genreIds = listOf(35),
                    character = "Guest Character",
                    episodeCount = 1,
                    popularity = 300.0,
                    voteAverage = 8.4,
                    voteCount = 9_000,
                ),
                TmdbPersonCreditDto(
                    id = 70,
                    mediaType = "tv",
                    name = "Law & Order",
                    genreIds = listOf(18, 80),
                    character = "Guest Character",
                    episodeCount = 1,
                    popularity = 180.0,
                    voteAverage = 7.8,
                    voteCount = 4_500,
                ),
                TmdbPersonCreditDto(
                    id = 80,
                    mediaType = "movie",
                    title = "Good Will Hunting",
                    character = "Sean Maguire",
                    order = 1,
                    popularity = 45.0,
                    voteAverage = 8.1,
                    voteCount = 13_000,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(80, result.first().id)
        assertEquals(listOf(80, 50, 60, 70), result.map { it.id })
    }

    @Test
    fun recurringTvRoleRanksAheadOfSingleEpisodeGuestInBiggerShow() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 90,
                    mediaType = "tv",
                    name = "Recurring Series",
                    genreIds = listOf(18),
                    character = "Lead",
                    episodeCount = 42,
                    popularity = 15.0,
                    voteAverage = 7.8,
                    voteCount = 500,
                ),
                TmdbPersonCreditDto(
                    id = 100,
                    mediaType = "tv",
                    name = "Massive Hit Show",
                    genreIds = listOf(18),
                    character = "Guest Character",
                    episodeCount = 1,
                    popularity = 500.0,
                    voteAverage = 8.5,
                    voteCount = 10_000,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(listOf(90, 100), result.map { it.id })
    }

    @Test
    fun leadingMovieBillingRanksAheadOfMinorMovieCredit() {
        val credits = TmdbCombinedCreditsDto(
            cast = listOf(
                TmdbPersonCreditDto(
                    id = 110,
                    mediaType = "movie",
                    title = "Lead Role",
                    character = "Lead",
                    order = 0,
                    popularity = 20.0,
                    voteAverage = 7.5,
                    voteCount = 1_000,
                ),
                TmdbPersonCreditDto(
                    id = 120,
                    mediaType = "movie",
                    title = "Minor Role",
                    character = "Minor",
                    order = 15,
                    popularity = 20.0,
                    voteAverage = 7.5,
                    voteCount = 1_000,
                ),
            ),
        )

        val result = credits.rankedRelevantPersonCredits("Acting")

        assertEquals(listOf(110, 120), result.map { it.id })
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
