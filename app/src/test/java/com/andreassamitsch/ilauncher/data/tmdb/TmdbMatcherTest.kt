package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbMatcherTest {
    @Test
    fun `accepts exact movie title and year`() {
        val lookup = parsed("Dune Part Two", MediaType.Movie, 2024)
        val match = TmdbMatcher.bestMatch(
            lookup,
            listOf(candidate(1, "Dune Part Two", MediaType.Movie, 2024)),
        )

        assertNotNull(match)
        assertEquals(1, match?.candidate?.id)
    }

    @Test
    fun `rejects weak title match`() {
        val lookup = parsed("Fallout", MediaType.Series, 2024)
        val match = TmdbMatcher.bestMatch(
            lookup,
            listOf(candidate(1, "The Last of Us", MediaType.Series, 2023)),
        )

        assertNull(match)
    }

    @Test
    fun `episode lookup accepts matching series candidate`() {
        val lookup = parsed("Fallout", MediaType.Episode, 2024)
        val match = TmdbMatcher.bestMatch(
            lookup,
            listOf(candidate(10, "Fallout", MediaType.Series, 2024)),
        )

        assertNotNull(match)
        assertEquals(10, match?.candidate?.id)
    }

    @Test
    fun `episode remains confident when source year is one year early`() {
        val lookup = parsed("ZeroZeroZero", MediaType.Episode, 2019)
        val match = TmdbMatcher.bestMatch(
            lookup,
            listOf(candidate(25, "ZeroZeroZero", MediaType.Series, 2020)),
        )

        assertNotNull(match)
        assertEquals(25, match?.candidate?.id)
    }

    @Test
    fun `large year mismatch prevents otherwise exact movie match`() {
        val lookup = parsed("Gladiator", MediaType.Movie, 2024)
        val match = TmdbMatcher.bestMatch(
            lookup,
            listOf(candidate(20, "Gladiator", MediaType.Movie, 2000)),
        )

        assertNull(match)
    }

    private fun parsed(title: String, type: MediaType, year: Int?) = ParsedMediaLookup(
        title = title,
        normalizedTitle = MediaTitleParser.normalizeTitle(title),
        typeHint = type,
        releaseYear = year,
        seasonNumber = if (type == MediaType.Episode) 1 else null,
        episodeNumber = if (type == MediaType.Episode) 1 else null,
    )

    private fun candidate(id: Int, title: String, type: MediaType, year: Int?) = TmdbCandidate(
        id = id,
        type = type,
        title = title,
        originalTitle = null,
        releaseYear = year,
        popularity = 10.0,
    )
}
