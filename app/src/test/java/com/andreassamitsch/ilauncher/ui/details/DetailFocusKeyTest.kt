package com.andreassamitsch.ilauncher.ui.details

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailFocusKeyTest {
    @Test
    fun tmdbIdentityStaysStableAcrossMetadataRefresh() {
        val source = MediaSource(provider = "tmdb_related", sourceId = "source")
        val before = MediaItem(
            id = "old",
            type = MediaType.Movie,
            title = "Title",
            tmdbId = 123,
            source = source,
        )
        val after = before.copy(id = "new", title = "Localized title", overview = "Loaded later")

        assertEquals(detailMediaFocusKey(before), detailMediaFocusKey(after))
        assertEquals("Movie:123", detailMediaFocusKey(after))
    }

    @Test
    fun nonTmdbItemsFallBackToInternalId() {
        val item = MediaItem(
            id = "local-42",
            type = MediaType.Unknown,
            title = "Local",
            source = MediaSource(provider = "local", sourceId = "42"),
        )

        assertEquals("Unknown:local-42", detailMediaFocusKey(item))
    }
}
