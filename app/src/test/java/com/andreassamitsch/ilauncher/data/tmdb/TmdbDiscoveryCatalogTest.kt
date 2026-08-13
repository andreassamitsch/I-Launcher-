package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryCatalogTest {
    @Test
    fun `movie and series catalogs expose stable unique row keys`() {
        listOf(MediaType.Movie, MediaType.Series).forEach { type ->
            val rows = TmdbDiscoveryCatalog.rows(type)
            assertEquals(8, rows.size)
            assertEquals(rows.size, rows.map { it.key }.distinct().size)
            assertTrue(rows.take(3).all { it.kind != TmdbDiscoveryRowKind.Genre })
            assertTrue(rows.drop(3).all { it.kind == TmdbDiscoveryRowKind.Genre })
        }
    }

    @Test
    fun `selected rows preserve user order and ignore unknown keys`() {
        val rows = TmdbDiscoveryCatalog.selectedRows(
            MediaType.Movie,
            listOf("movie-genre-53", "unknown", "movie-trending"),
        )

        assertEquals(listOf("movie-genre-53", "movie-trending"), rows.map { it.key })
    }
}
