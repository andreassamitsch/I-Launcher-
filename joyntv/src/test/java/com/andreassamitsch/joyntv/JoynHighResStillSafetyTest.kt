package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynHighResStillSafetyTest {
    @Test fun derivesOriginalFromExactlyTheSameJoynImageIdentity() {
        val rendition = "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-livestill-503x283"
        assertEquals(
            "https://img.joyn.de/ingest/t_001/i_p3htxmwhu58j_f8fb7582.jpg",
            JoynHighResEpisodeArtwork.originalCandidate(rendition),
        )
    }

    @Test fun rejectsPathThatDoesNotRepresentAnImageInTheJoynIngestCatalogue() {
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://img.joyn.de/ingest/t_001/not-an-image/profile:nextgen-web-livestill-503x283",
        ))
        assertNull(JoynHighResEpisodeArtwork.originalCandidate(
            "https://img.joyn.de/other/path.jpg/profile:nextgen-web-livestill-503x283",
        ))
    }
}
