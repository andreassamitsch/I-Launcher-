package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTitleParserTest {
    @Test
    fun `parses season and episode from SxxExx title`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "Fallout S02E04"),
        )

        assertEquals("Fallout", parsed.title)
        assertEquals("fallout", parsed.normalizedTitle)
        assertEquals(2, parsed.seasonNumber)
        assertEquals(4, parsed.episodeNumber)
        assertEquals(MediaType.Episode, parsed.typeHint)
    }

    @Test
    fun `extracts release year without polluting normalized title`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "Dune: Part Two (2024)", typeHint = MediaType.Movie),
        )

        assertEquals("Dune: Part Two", parsed.title)
        assertEquals("dune part two", parsed.normalizedTitle)
        assertEquals(2024, parsed.releaseYear)
        assertEquals(MediaType.Movie, parsed.typeHint)
    }

    @Test
    fun `normalizes accents for stable cache keys`() {
        assertEquals("amelie", MediaTitleParser.normalizeTitle("Amélie"))
    }
}
