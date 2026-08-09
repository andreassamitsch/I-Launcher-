package com.andreassamitsch.ilauncher.data.search

import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    private val repository = SearchRepository()

    @Test
    fun `normalizes accents and punctuation for TV search`() {
        assertEquals("osterreich heute", repository.normalize("Österreich – Heute!"))
        assertTrue(repository.scoreText("oster", "Österreich heute") > 0)
    }

    @Test
    fun `prefers source content before EPG when title scores tie`() {
        val now = 1_000_000L
        val preview = AppContentChannel(
            id = "channel-1",
            sourceOrder = 0,
            packageName = "example.app",
            title = "Empfohlen",
            appLinkIntentUri = null,
            programs = listOf(
                AppContentProgram(
                    sourceOrder = 0,
                    media = media("Tatort", "preview-1"),
                ),
            ),
        )
        val liveChannel = LiveTvChannel(serviceReference = "1:0:1", name = "ORF 1")
        val epg = EpgState(
            guideByServiceReference = mapOf(
                liveChannel.serviceReference to listOf(
                    program("Tatort", start = now + 60_000L, duration = 3_600_000L),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "Tatort",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = listOf(preview),
            liveTvChannels = listOf(liveChannel),
            epgState = epg,
            nowUtcMillis = now,
        )

        assertEquals(SearchResultKind.PreviewProgram, results[0].kind)
        assertEquals(SearchResultKind.EpgProgram, results[1].kind)
    }

    @Test
    fun `does not return already ended EPG programmes`() {
        val now = 10_000_000L
        val channel = LiveTvChannel(serviceReference = "ref", name = "ORF 1")
        val epg = EpgState(
            guideByServiceReference = mapOf(
                "ref" to listOf(
                    program("Vergangene Sendung", start = now - 7_200_000L, duration = 3_600_000L),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "Vergangene",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = emptyList(),
            liveTvChannels = listOf(channel),
            epgState = epg,
            nowUtcMillis = now,
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `finds future programme by channel name metadata`() {
        val now = 20_000_000L
        val channel = LiveTvChannel(serviceReference = "ref", name = "ORF 1")
        val epg = EpgState(
            guideByServiceReference = mapOf(
                "ref" to listOf(
                    program("Nachrichten", start = now + 60_000L, duration = 1_800_000L),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "ORF",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = emptyList(),
            liveTvChannels = listOf(channel),
            epgState = epg,
            nowUtcMillis = now,
        )

        assertEquals(1, results.size)
        assertEquals("ORF 1", results.single().sourceLabel)
        assertEquals(SearchResultKind.EpgProgram, results.single().kind)
    }

    @Test
    fun `requires two characters for local search`() {
        val results = repository.searchLocal(
            query = "a",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = emptyList(),
            liveTvChannels = emptyList(),
            epgState = EpgState(),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `two character search does not scan preview overviews`() {
        val preview = AppContentChannel(
            id = "channel-1",
            sourceOrder = 0,
            packageName = "example.app",
            title = "Empfohlen",
            appLinkIntentUri = null,
            programs = listOf(
                AppContentProgram(
                    sourceOrder = 0,
                    media = media(
                        title = "Tatort",
                        sourceId = "preview-1",
                        overview = "XY steckt nur in einer langen Beschreibung.",
                    ),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "xy",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = listOf(preview),
            liveTvChannels = emptyList(),
            epgState = EpgState(),
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `three character search still searches preview overviews`() {
        val preview = AppContentChannel(
            id = "channel-1",
            sourceOrder = 0,
            packageName = "example.app",
            title = "Empfohlen",
            appLinkIntentUri = null,
            programs = listOf(
                AppContentProgram(
                    sourceOrder = 0,
                    media = media(
                        title = "Tatort",
                        sourceId = "preview-1",
                        overview = "Weltraumreise mit unbekanntem Ziel.",
                    ),
                ),
            ),
        )

        val results = repository.searchLocal(
            query = "wel",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = listOf(preview),
            liveTvChannels = emptyList(),
            epgState = EpgState(),
        )

        assertEquals(1, results.size)
        assertEquals(SearchResultKind.PreviewProgram, results.single().kind)
    }

    @Test
    fun `deduplicates duplicate EPG programme identities`() {
        val now = 30_000_000L
        val channel = LiveTvChannel(serviceReference = "ref", name = "ORF 1")
        val duplicate = program(
            title = "Nachrichten",
            start = now + 60_000L,
            duration = 1_800_000L,
        )
        val epg = EpgState(
            guideByServiceReference = mapOf(
                "ref" to listOf(duplicate, duplicate.copy()),
            ),
        )

        val results = repository.searchLocal(
            query = "Nachrichten",
            apps = emptyList(),
            watchNextItems = emptyList(),
            previewChannels = emptyList(),
            liveTvChannels = listOf(channel),
            epgState = epg,
            nowUtcMillis = now,
        )

        assertEquals(1, results.size)
        assertEquals(SearchResultKind.EpgProgram, results.single().kind)
    }

    private fun media(
        title: String,
        sourceId: String,
        overview: String? = null,
    ) = MediaItem(
        id = sourceId,
        type = MediaType.Movie,
        title = title,
        overview = overview,
        source = MediaSource(
            provider = "test",
            sourceId = sourceId,
        ),
    )

    private fun program(title: String, start: Long, duration: Long) = LiveTvProgram(
        eventId = null,
        title = title,
        startUtcMillis = start,
        durationMillis = duration,
    )
}
