package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class AiringIdentityTest {
    @Test fun remakeUsesEpisodeYearNotPremiere() {
        val lookup = MediaTitleParser.parse(MediaLookup("MacGyver", MediaType.Episode, seasonNumber = 2, episodeNumber = 1,
            strictAiringIdentity = true, episodeAirYear = 2017, episodeTitle = "Folge"))
        assertEquals(null, lookup.releaseYear)
        assertEquals(0, airingEpisodeEvidence(lookup, "Folge", 1986))
        assertEquals(3, airingEpisodeEvidence(lookup, "Folge", 2017))
    }
}
