package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServusPlaybackMetadataTest {
    private val item = ServusNewsEpisode("episode", "Folge 2", "Serie", null, 1000, null, "https://example.com/episode.jpg")

    @Test fun episodePublishesExactTitleAndArtwork() {
        val metadata = ServusPlaybackMetadata.episode(item)
        assertEquals("Folge 2", metadata.title)
        assertEquals("Serie", metadata.contextTitle)
        assertEquals(item.artworkUri, metadata.artworkUri)
    }

    @Test fun matchesExactEpisodeInCollection() {
        val show = ServusShow("show", "Serie", null, "cat", "Kategorie", null, null, null, emptyList(),
            listOf(ServusShowCollection("collection", "Folgen", episodes = listOf(item))))
        assertEquals(item, ServusPlaybackMetadata.findEpisode("episode", emptyList(), listOf(show)))
        assertNull(ServusPlaybackMetadata.findEpisode("other", emptyList(), listOf(show)))
    }

    @Test fun liveDoesNotPublishUpcomingProgram() {
        val channel = ServusLiveChannel("live", "ServusTV Live", null, null, null, null,
            listOf(ServusLiveProgram("program", "Nachrichten", null, null, 1000, 2000)))
        assertEquals("ServusTV Live", ServusPlaybackMetadata.live(channel, 999).title)
        assertEquals("Nachrichten", ServusPlaybackMetadata.live(channel, 1000).title)
        assertEquals("ServusTV Live", ServusPlaybackMetadata.live(channel, 2000).title)
    }
}
