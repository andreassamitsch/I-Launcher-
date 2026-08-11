package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbHeroBackdropPolicyTest {
    @Test
    fun primaryBackdropPathWins() {
        val details = TmdbMediaDetailsDto(
            backdropPath = "/primary.jpg",
            images = TmdbImagesDto(
                backdrops = listOf(
                    TmdbImageDto(filePath = "/fallback.jpg", language = null, voteAverage = 10.0),
                ),
            ),
        )

        assertEquals("/primary.jpg", details.preferredHeroBackdropPath())
    }

    @Test
    fun missingPrimaryBackdropUsesHighestRatedLanguageNeutralBackdrop() {
        val details = TmdbMediaDetailsDto(
            images = TmdbImagesDto(
                backdrops = listOf(
                    TmdbImageDto(filePath = "/english.jpg", language = "en", voteAverage = 10.0),
                    TmdbImageDto(filePath = "/neutral-low.jpg", language = null, voteAverage = 7.0),
                    TmdbImageDto(filePath = "/neutral-high.jpg", language = null, voteAverage = 9.0),
                ),
            ),
        )

        assertEquals("/neutral-high.jpg", details.preferredHeroBackdropPath())
    }
}
