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
}
