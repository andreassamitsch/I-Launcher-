package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbSearchYearFallbackTest {
    @Test
    fun `known source year is tried strictly before unfiltered fallback`() {
        assertEquals(listOf(2019, null), tmdbSearchYearAttempts(2019))
    }

    @Test
    fun `missing source year performs only unfiltered search`() {
        assertEquals(listOf<Int?>(null), tmdbSearchYearAttempts(null))
    }
}
