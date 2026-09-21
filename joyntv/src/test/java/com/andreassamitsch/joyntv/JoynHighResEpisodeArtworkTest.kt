package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynHighResEpisodeArtworkTest {
    @Test fun stripsOnlyTheRenditionOfTheSameApiImage() {
        val apiUrl = "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-livestill-503x283"
        assertEquals("https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg",
            JoynHighResEpisodeArtwork.originalCandidate(apiUrl))
    }

    @Test fun neverRewritesUnrelatedHostsOrAlreadyUnprofiledImages() {
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://example.com/ingest/t_001/episode.jpg/profile:nextgen-web-livestill-503x283"))
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://img.joyn.de/ingest/t_001/episode.jpg"))
    }
}
