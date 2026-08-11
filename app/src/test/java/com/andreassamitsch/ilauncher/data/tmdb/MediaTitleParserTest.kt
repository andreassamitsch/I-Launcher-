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
    fun `parses colon separated episode marker before title`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "S3:E2 Westworld"),
        )

        assertEquals("Westworld", parsed.title)
        assertEquals("westworld", parsed.normalizedTitle)
        assertEquals(3, parsed.seasonNumber)
        assertEquals(2, parsed.episodeNumber)
        assertEquals(MediaType.Episode, parsed.typeHint)
    }

    @Test
    fun `parses prefixed episode and trailing source year independently`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "S1:E1 ZeroZeroZero (2019)"),
        )

        assertEquals("ZeroZeroZero", parsed.title)
        assertEquals("zerozerozero", parsed.normalizedTitle)
        assertEquals(2019, parsed.releaseYear)
        assertEquals(1, parsed.seasonNumber)
        assertEquals(1, parsed.episodeNumber)
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
    fun `strips provider playback qualifier from movie title`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "Tenet [dt./OV]", typeHint = MediaType.Movie),
        )

        assertEquals("Tenet", parsed.title)
        assertEquals("tenet", parsed.normalizedTitle)
        assertEquals(MediaType.Movie, parsed.typeHint)
    }

    @Test
    fun `strips playback qualifier while preserving source year`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "Top Gun: Maverick (2022) [dt./OV]", typeHint = MediaType.Movie),
        )

        assertEquals("Top Gun: Maverick", parsed.title)
        assertEquals("top gun maverick", parsed.normalizedTitle)
        assertEquals(2022, parsed.releaseYear)
    }

    @Test
    fun `does not strip arbitrary bracketed title suffix`() {
        val parsed = MediaTitleParser.parse(
            MediaLookup(rawTitle = "Blade Runner [Final Cut]", typeHint = MediaType.Movie),
        )

        assertEquals("Blade Runner [Final Cut]", parsed.title)
    }

    @Test
    fun `normalizes accents for stable cache keys`() {
        assertEquals("amelie", MediaTitleParser.normalizeTitle("Amélie"))
    }
}
