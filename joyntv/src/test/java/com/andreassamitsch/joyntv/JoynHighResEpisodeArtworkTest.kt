package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynHighResEpisodeArtworkTest {
    private val apiUrl =
        "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-livestill-503x283"
    private val original = "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg"
    private val primarycut = "$original/profile:nextgen-web-primarycut-1920x1080"

    @Test fun stripsOnlyTheRenditionOfTheSameApiImage() {
        assertEquals(original, JoynHighResEpisodeArtwork.originalCandidate(apiUrl))
    }

    @Test fun derivesVerifiedFullHdRenditionFromExactlyTheApiImageId() {
        assertEquals(primarycut, JoynHighResEpisodeArtwork.primarycutCandidate(apiUrl))
        assertEquals(listOf(primarycut, original), JoynHighResEpisodeArtwork.renditionCandidates(apiUrl))
        val anotherEpisode = apiUrl.replace("i_p3htxmwhu58j_f8fb7582", "some_other_episode")
        assertEquals(
            primarycut.replace("i_p3htxmwhu58j_f8fb7582", "some_other_episode"),
            JoynHighResEpisodeArtwork.primarycutCandidate(anotherEpisode),
        )
    }

    @Test fun doesNotProbeAlreadySelectedPrimarycutTwice() {
        assertEquals(listOf(original), JoynHighResEpisodeArtwork.renditionCandidates(primarycut))
    }

    @Test fun neverRewritesUnrelatedHostsOrAlreadyUnprofiledImages() {
        val unrelatedHost =
            "https://example.com/ingest/t_001/episode.jpg/profile:nextgen-web-livestill-503x283"
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(unrelatedHost))
        assertNull(JoynHighResEpisodeArtwork.primarycutCandidate(unrelatedHost))
        assertTrue(JoynHighResEpisodeArtwork.renditionCandidates(unrelatedHost).isEmpty())
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(original))
    }

    @Test fun onlyPromotesRealLargerWideImages() {
        assertTrue(JoynHighResEpisodeArtwork.isLargerHeroImage(1920 to 1080, 503 to 283))
        assertTrue(JoynHighResEpisodeArtwork.isLargerHeroImage(3840 to 2160, 1920 to 1080))
        assertFalse(JoynHighResEpisodeArtwork.isLargerHeroImage(503 to 283, 503 to 283))
        assertFalse(JoynHighResEpisodeArtwork.isLargerHeroImage(1920 to 1080, 3840 to 2160))
        assertFalse(JoynHighResEpisodeArtwork.isLargerHeroImage(1200 to 1800, 503 to 283))
    }
}
