package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbSearchRankerTest {
    @Test
    fun `exact original title wins localized Star Wars search`() {
        val results = listOf(
            result(
                id = 1,
                title = "Star Wars: Visionen",
                originalTitle = "Star Wars: Visions",
                voteCount = 900,
                popularity = 30.0,
            ),
            result(
                id = 2,
                title = "Krieg der Sterne",
                originalTitle = "Star Wars",
                voteCount = 21_000,
                popularity = 16.0,
            ),
            result(
                id = 3,
                title = "Star Wars: Die letzten Jedi",
                originalTitle = "Star Wars: The Last Jedi",
                voteCount = 15_000,
                popularity = 20.0,
            ),
        )

        val ranked = TmdbSearchRanker.rank("Star Wars", results)

        assertEquals(2, ranked[0].id)
        assertEquals(3, ranked[1].id)
        assertEquals(1, ranked[2].id)
    }

    @Test
    fun `established result wins within same prefix tier`() {
        val results = listOf(
            result(id = 1, title = "Dune: Prophecy", voteCount = 700, popularity = 90.0),
            result(id = 2, title = "Dune: Part Two", voteCount = 8_000, popularity = 40.0),
        )

        val ranked = TmdbSearchRanker.rank("Dune", results)

        assertEquals(listOf(2, 1), ranked.map { it.id })
    }

    @Test
    fun `popular franchise result can beat obscure exact collision for partial query`() {
        val results = listOf(
            result(id = 1, title = "Expend", voteCount = 12, popularity = 2.0),
            result(id = 2, title = "The Expendables 4", voteCount = 2_000, popularity = 35.0),
            result(id = 3, title = "Expendable Assets", voteCount = 80, popularity = 4.0),
        )

        val ranked = TmdbSearchRanker.rank("expend", results)

        assertEquals(2, ranked.first().id)
    }

    @Test
    fun `word prefix after leading article is treated as strong title match`() {
        val item = result(
            id = 1,
            title = "The Expendables 4",
            voteCount = 0,
            popularity = 0.0,
        )

        assertEquals(900, TmdbSearchRanker.titleMatchScore("expend", item))
    }

    private fun result(
        id: Int,
        title: String,
        originalTitle: String = title,
        voteCount: Int,
        popularity: Double,
    ) = TmdbSearchResultDto(
        id = id,
        mediaType = "movie",
        title = title,
        originalTitle = originalTitle,
        voteCount = voteCount,
        popularity = popularity,
        voteAverage = 8.0,
    )
}
