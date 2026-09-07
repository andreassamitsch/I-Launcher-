package com.andreassamitsch.ilauncher.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryCategoryResultIdTest {
    @Test
    fun categoryResultRestoresItsParentCategory() {
        assertEquals(
            "movie-genre-28",
            discoveryCategoryKeyFromResultId(
                "search:tmdb-category:movie-genre-28:Movie:123",
            ),
        )
    }

    @Test
    fun normalDiscoveryResultDoesNotOpenCategoryPage() {
        assertNull(discoveryCategoryKeyFromResultId("search:tmdb:Movie:123"))
        assertNull(discoveryCategoryKeyFromResultId(null))
    }
}
