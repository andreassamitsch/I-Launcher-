package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbRelationsMappingTest {
    @Test
    fun similarMoviesExcludeCurrentAdultAndInvalidEntriesWithoutReordering() {
        val results = listOf(
            TmdbSearchResultDto(id = 10, title = "Current"),
            TmdbSearchResultDto(id = 20, title = "First", releaseDate = "2024-01-01"),
            TmdbSearchResultDto(id = 30, title = "Adult", adult = true),
            TmdbSearchResultDto(id = 0, title = "Invalid"),
            TmdbSearchResultDto(id = 40, title = "Second", releaseDate = "2022-01-01"),
        )

        val mapped = mapTmdbRelationItems(
            results = results,
            type = MediaType.Movie,
            currentTmdbId = 10,
            imageConfiguration = null,
            limit = 16,
        )

        assertEquals(listOf(20, 40), mapped.map { it.tmdbId })
        assertEquals(listOf("First", "Second"), mapped.map { it.title })
        assertEquals(listOf(2024, 2022), mapped.map { it.releaseYear })
    }

    @Test
    fun seriesRelationsUseTvNamesAndRespectRowLimit() {
        val results = listOf(
            TmdbSearchResultDto(id = 1, name = "One", firstAirDate = "2021-05-01"),
            TmdbSearchResultDto(id = 2, name = "Two", firstAirDate = "2020-05-01"),
            TmdbSearchResultDto(id = 3, name = "Three", firstAirDate = "2019-05-01"),
        )

        val mapped = mapTmdbRelationItems(
            results = results,
            type = MediaType.Series,
            currentTmdbId = 99,
            imageConfiguration = null,
            limit = 2,
        )

        assertEquals(listOf(1, 2), mapped.map { it.tmdbId })
        assertEquals(listOf("One", "Two"), mapped.map { it.title })
    }

    @Test
    fun toyStoryLikeSourceRejectsSingleGenreComedyOutlier() {
        val recommendations = listOf(
            TmdbSearchResultDto(
                id = 20,
                title = "Animation Adventure",
                genreIds = listOf(16, 12, 10751),
            ),
            TmdbSearchResultDto(
                id = 30,
                title = "Tropic Thunder",
                genreIds = listOf(28, 35, 10752),
            ),
            TmdbSearchResultDto(
                id = 40,
                title = "Family Adventure",
                genreIds = listOf(12, 10751, 28),
            ),
        )
        val similar = listOf(
            TmdbSearchResultDto(
                id = 50,
                title = "Chicken Run",
                genreIds = listOf(16, 35, 10751),
            ),
            TmdbSearchResultDto(
                id = 20,
                title = "Duplicate",
                genreIds = listOf(16, 12, 10751),
            ),
        )

        val selected = selectTmdbRelatedCandidates(
            recommendations = recommendations,
            similar = similar,
            sourceGenreIds = listOf(16, 12, 10751, 35),
            currentTmdbId = 10,
        )

        assertEquals(listOf(20, 50, 40), selected.map { it.id })
    }

    @Test
    fun threeOrMoreSourceGenresRequireAtLeastTwoSharedGenres() {
        val selected = selectTmdbRelatedCandidates(
            recommendations = listOf(
                TmdbSearchResultDto(id = 1, title = "Drama only", genreIds = listOf(18)),
                TmdbSearchResultDto(id = 2, title = "Crime drama", genreIds = listOf(18, 80)),
                TmdbSearchResultDto(id = 3, title = "Mystery drama", genreIds = listOf(18, 9648)),
            ),
            similar = emptyList(),
            sourceGenreIds = listOf(18, 80, 9648),
            currentTmdbId = 99,
        )

        assertEquals(listOf(2, 3), selected.map { it.id })
    }

    @Test
    fun oneOrTwoGenreSourcesOnlyRequireOneSharedGenre() {
        val selected = selectTmdbRelatedCandidates(
            recommendations = listOf(
                TmdbSearchResultDto(id = 1, name = "Comedy", genreIds = listOf(35)),
                TmdbSearchResultDto(id = 2, name = "Drama", genreIds = listOf(18)),
            ),
            similar = emptyList(),
            sourceGenreIds = listOf(35),
            currentTmdbId = 99,
        )

        assertEquals(listOf(1), selected.map { it.id })
    }

    @Test
    fun missingSourceGenresFallsBackToTmdbFeedOrder() {
        val selected = selectTmdbRelatedCandidates(
            recommendations = listOf(
                TmdbSearchResultDto(id = 1, title = "Recommended"),
                TmdbSearchResultDto(id = 2, title = "Second recommendation"),
            ),
            similar = listOf(
                TmdbSearchResultDto(id = 2, title = "Duplicate"),
                TmdbSearchResultDto(id = 3, title = "Similar"),
            ),
            sourceGenreIds = emptyList(),
            currentTmdbId = 99,
        )

        assertEquals(listOf(1, 2, 3), selected.map { it.id })
    }
}
