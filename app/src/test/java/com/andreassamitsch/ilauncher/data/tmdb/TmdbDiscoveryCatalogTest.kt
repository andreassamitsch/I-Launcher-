package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryCatalogTest {
    @Test
    fun expandedCatalogKeepsUniqueKeysAndCompactDefaults() {
        val movies = TmdbDiscoveryCatalog.rows(MediaType.Movie)
        val series = TmdbDiscoveryCatalog.rows(MediaType.Series)
        assertEquals(25, movies.size)
        assertEquals(22, series.size)
        assertEquals(movies.size, movies.map { it.key }.distinct().size)
        assertEquals(series.size, series.map { it.key }.distinct().size)
        assertEquals(8, TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Movie).size)
        assertEquals(8, TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Series).size)
        assertTrue(movies.any { it.key == "movie-now-playing" })
        assertTrue(series.any { it.key == "series-on-the-air" })
    }

    @Test
    fun selectedRowsPreserveOrderAndIgnoreUnknownKeys() {
        val rows = TmdbDiscoveryCatalog.selectedRows(
            MediaType.Movie,
            listOf("movie-genre-53", "unknown", "movie-trending"),
        )
        assertEquals(listOf("movie-genre-53", "movie-trending"), rows.map { it.key })
    }
}
