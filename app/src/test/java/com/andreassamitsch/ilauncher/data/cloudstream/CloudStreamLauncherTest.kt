package com.andreassamitsch.ilauncher.data.cloudstream

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CloudStreamLauncherTest {
    @Test
    fun `movie direct play uri carries stable media identity`() {
        val uri = buildCloudStreamPlayUri(
            CloudStreamMediaRequest(
                title = "Dune: Part Two",
                originalTitle = "Dune: Part Two",
                year = 2024,
                type = MediaType.Movie,
                tmdbId = 693134,
                imdbId = "tt15239678",
            ),
        )

        val parsed = URI(uri)
        val params = parsed.queryParameters()
        assertEquals("cloudstreamplay", parsed.scheme)
        assertEquals("v1", parsed.host)
        assertEquals("Dune: Part Two", params["title"])
        assertEquals("2024", params["year"])
        assertEquals("movie", params["type"])
        assertEquals("693134", params["tmdbId"])
        assertEquals("tt15239678", params["imdbId"])
        assertFalse(params.containsKey("season"))
        assertFalse(params.containsKey("episode"))
    }

    @Test
    fun `episode direct play uri preserves unicode and exact episode identity`() {
        val uri = buildCloudStreamPlayUri(
            CloudStreamMediaRequest(
                title = "Die drei ???",
                originalTitle = "The Three Investigators",
                type = MediaType.Episode,
                season = 2,
                episode = 4,
                episodeTitle = "Spur & Rätsel / Teil 1",
                tmdbId = 123,
                tmdbEpisodeId = 456,
            ),
        )

        val params = URI(uri).queryParameters()
        assertEquals("Die drei ???", params["title"])
        assertEquals("The Three Investigators", params["originalTitle"])
        assertEquals("episode", params["type"])
        assertEquals("2", params["season"])
        assertEquals("4", params["episode"])
        assertEquals("Spur & Rätsel / Teil 1", params["episodeTitle"])
        assertEquals("123", params["tmdbId"])
        assertEquals("456", params["tmdbEpisodeId"])
    }

    @Test
    fun `media item mapping normalizes titles and keeps external ids`() {
        val request = MediaItem(
            id = "test",
            type = MediaType.Series,
            title = "  Fallout   ",
            originalTitle = " Fallout\n",
            releaseYear = 2024,
            tmdbId = 106379,
            imdbId = " tt12637874 ",
            source = MediaSource(provider = "tmdb", sourceId = "106379"),
        ).toCloudStreamMediaRequest()

        assertEquals("Fallout", request.title)
        assertEquals("Fallout", request.originalTitle)
        assertEquals(2024, request.year)
        assertEquals(106379, request.tmdbId)
        assertEquals("tt12637874", request.imdbId)
        assertNull(request.season)
        assertNull(request.episode)
    }

    @Test
    fun `blank optional values are omitted from protocol`() {
        val params = URI(
            buildCloudStreamPlayUri(
                CloudStreamMediaRequest(
                    title = " Test ",
                    originalTitle = "   ",
                    type = MediaType.Unknown,
                    imdbId = " ",
                ),
            ),
        ).queryParameters()

        assertEquals("Test", params["title"])
        assertEquals("unknown", params["type"])
        assertFalse(params.containsKey("originalTitle"))
        assertFalse(params.containsKey("imdbId"))
    }
}

private fun URI.queryParameters(): Map<String, String> =
    rawQuery.orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
