package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbAlternateBackdropTest {
    @Test
    fun usesDifferentNeutralBackdropWhenAvailable() {
        val details = TmdbMediaDetailsDto(
            backdropPath = "/primary.jpg",
            images = TmdbImagesDto(
                backdrops = listOf(
                    TmdbImageDto(filePath = "/primary.jpg", language = null, voteAverage = 10.0),
                    TmdbImageDto(filePath = "/other-en.jpg", language = "en", voteAverage = 10.0),
                    TmdbImageDto(filePath = "/other.jpg", language = null, voteAverage = 8.0),
                ),
            ),
        )
        assertEquals("/other.jpg", details.preferredDiscoveryHeroBackdropPath())
    }

    @Test
    fun fallsBackToPrimaryWhenNoAlternativeExists() {
        val details = TmdbMediaDetailsDto(backdropPath = "/primary.jpg")
        assertEquals("/primary.jpg", details.preferredDiscoveryHeroBackdropPath())
    }
}
