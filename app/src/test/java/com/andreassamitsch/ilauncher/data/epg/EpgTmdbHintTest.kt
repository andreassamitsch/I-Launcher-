package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgTmdbHintTest {
    @Test
    fun `uncategorized programme keeps TMDB multi search available`() {
        assertEquals(MediaType.Unknown, epgMediaTypeHint(program()))
    }

    @Test
    fun `series category maps to series`() {
        assertEquals(MediaType.Series, epgMediaTypeHint(program(categories = listOf("Serie"))))
    }

    @Test
    fun `movie category maps to movie`() {
        assertEquals(MediaType.Movie, epgMediaTypeHint(program(categories = listOf("Spielfilm"))))
    }

    @Test
    fun `season and episode numbers take precedence`() {
        assertEquals(
            MediaType.Episode,
            epgMediaTypeHint(program(categories = listOf("Spielfilm"), season = 2, episode = 7)),
        )
    }

    private fun program(
        categories: List<String>? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = LiveTvProgram(
        eventId = null,
        title = "Test",
        startUtcMillis = 1_000L,
        durationMillis = 60_000L,
        categories = categories,
        seasonNumber = season,
        episodeNumber = episode,
    )
}
