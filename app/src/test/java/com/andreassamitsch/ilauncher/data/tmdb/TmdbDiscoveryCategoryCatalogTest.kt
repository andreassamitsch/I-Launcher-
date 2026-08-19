package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryCategoryCatalogTest {
    @Test
    fun genrePageGetsSixDistinctQualityViews() {
        val parent = TmdbDiscoveryCatalog.rows(MediaType.Movie)
            .first { it.key == "movie-genre-28" }

        val rows = TmdbDiscoveryCategoryCatalog.rows(MediaType.Movie, parent)

        assertEquals(6, rows.size)
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(
            listOf(
                TmdbDiscoveryCategoryRowKind.Popular,
                TmdbDiscoveryCategoryRowKind.TopRated,
                TmdbDiscoveryCategoryRowKind.RecentPopular,
                TmdbDiscoveryCategoryRowKind.RecentTopRated,
                TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
                TmdbDiscoveryCategoryRowKind.Classics,
            ),
            rows.map { it.kind },
        )
        assertTrue(rows.all { it.key.startsWith("movie-genre-28:more:") })
    }

    @Test
    fun movieCinemaPageKeepsMovieOnlyRows() {
        val parent = TmdbDiscoveryCatalog.rows(MediaType.Movie)
            .first { it.key == "movie-now-playing" }

        val rows = TmdbDiscoveryCategoryCatalog.rows(MediaType.Movie, parent)

        assertTrue(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.NowPlaying })
        assertTrue(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.Upcoming })
        assertFalse(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.AiringToday })
        assertFalse(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.OnTheAir })
    }

    @Test
    fun seriesOnAirPageKeepsSeriesOnlyRows() {
        val parent = TmdbDiscoveryCatalog.rows(MediaType.Series)
            .first { it.key == "series-on-the-air" }

        val rows = TmdbDiscoveryCategoryCatalog.rows(MediaType.Series, parent)

        assertTrue(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.OnTheAir })
        assertTrue(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.AiringToday })
        assertFalse(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.NowPlaying })
        assertFalse(rows.any { it.kind == TmdbDiscoveryCategoryRowKind.Upcoming })
    }

    @Test
    fun topRatedPagePrioritizesQualityViews() {
        val parent = TmdbDiscoveryCatalog.rows(MediaType.Series)
            .first { it.key == "series-top-rated" }

        val rows = TmdbDiscoveryCategoryCatalog.rows(MediaType.Series, parent)

        assertEquals(TmdbDiscoveryCategoryRowKind.TopRated, rows.first().kind)
        assertEquals(TmdbDiscoveryCategoryRowKind.RecentTopRated, rows[1].kind)
        assertEquals(TmdbDiscoveryCategoryRowKind.AllTimeTopRated, rows[2].kind)
    }
}
