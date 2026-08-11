package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewResolverTypeHintTest {
    @Test
    fun `preview movie hint is relaxed because provider type may be wrong`() {
        val media = media(type = MediaType.Movie)

        assertEquals(MediaType.Unknown, resolverTypeHint(media))
    }

    @Test
    fun `preview episode coordinates remain authoritative`() {
        val media = media(
            type = MediaType.Movie,
            seasonNumber = 1,
            episodeNumber = 3,
        )

        assertEquals(MediaType.Episode, resolverTypeHint(media))
    }

    @Test
    fun `non preview providers keep their declared type`() {
        val media = media(
            type = MediaType.Movie,
            provider = "android_watch_next",
        )

        assertEquals(MediaType.Movie, resolverTypeHint(media))
    }

    @Test
    fun `preview resolver key is versioned to bypass stale negative mappings`() {
        val media = media(type = MediaType.Movie)

        assertEquals("test-source:preview-resolver-v2", resolverSourceKey(media))
    }

    @Test
    fun `watch next resolver key remains stable`() {
        val media = media(type = MediaType.Movie, provider = "android_watch_next")

        assertEquals("test-source", resolverSourceKey(media))
    }

    private fun media(
        type: MediaType,
        provider: String = "android_preview_channel",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = MediaItem(
        id = "test",
        type = type,
        title = "Example",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        source = MediaSource(
            provider = provider,
            sourceId = "test-source",
        ),
    )
}
