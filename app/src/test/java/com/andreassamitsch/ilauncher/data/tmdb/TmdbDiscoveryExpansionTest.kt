package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryExpansionTest {
    @Test
    fun optionalRowsExpandCatalogWithoutExpandingDefaults() {
        val movies = TmdbDiscoveryCatalog.rows(MediaType.Movie)
        val series = TmdbDiscoveryCatalog.rows(MediaType.Series)

        assertTrue(movies.size > TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Movie).size)
        assertTrue(series.size > TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Series).size)
        assertEquals(8, TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Movie).size)
        assertEquals(8, TmdbDiscoveryCatalog.defaultRowKeys(MediaType.Series).size)
        assertTrue(movies.any { it.key == "movie-now-playing" })
        assertTrue(movies.any { it.key == "movie-upcoming" })
        assertTrue(series.any { it.key == "series-airing-today" })
        assertTrue(series.any { it.key == "series-on-the-air" })
    }

    @Test
    fun selectionKeepsRequestedOrderAndIgnoresUnknownRows() {
        val selected = TmdbDiscoveryCatalog.selectedRows(
            MediaType.Movie,
            listOf("movie-upcoming", "unknown", "movie-trending"),
        )

        assertEquals(listOf("movie-upcoming", "movie-trending"), selected.map { it.key })
    }
}
