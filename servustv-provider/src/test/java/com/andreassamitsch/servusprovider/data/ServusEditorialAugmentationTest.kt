package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServusEditorialAugmentationTest {
    @Test
    fun weatherNinetySecondShowIsInsertedAfterMainWeatherWithoutDuplicate() {
        val weather = ServusShow(
            id = "WEATHER-MAIN",
            title = "Servus Wetter",
            description = "Wetter",
            categoryId = "NEWS",
            categoryTitle = "News & Magazine",
            artworkUri = "https://example.invalid/weather.webp",
            squareArtworkUri = "https://example.invalid/weather-square.webp",
            logoUri = "https://example.invalid/weather-logo.webp",
            episodes = emptyList(),
        )
        val categories = listOf(
            ServusCategory(
                id = "NEWS",
                title = "News & Magazine",
                order = 0,
                shows = listOf(weather),
            ),
        )

        val augmented = ServusCatalogAugmentation.withEditorialShows(categories, emptyList())
        val shows = augmented.single().shows

        assertEquals(listOf("WEATHER-MAIN", ServusBranding.WEATHER_90_SECONDS_SHOW_ID), shows.map { it.id })
        val weather90 = shows.last()
        assertEquals(ServusBranding.WEATHER_90_SECONDS_SHOW_NAME, weather90.title)
        assertEquals(ServusBranding.WEATHER_90_SECONDS_DESCRIPTION, weather90.description)
        assertEquals(weather.artworkUri, weather90.artworkUri)
        assertEquals(weather.logoUri, weather90.logoUri)
    }

    @Test
    fun existingWeatherNinetySecondProductIsCanonicalizedButNotDuplicated() {
        val existing = ServusShow(
            id = ServusBranding.WEATHER_90_SECONDS_SHOW_ID,
            title = "Wetter kompakt",
            description = null,
            categoryId = "NEWS",
            categoryTitle = "News & Magazine",
            artworkUri = "https://example.invalid/dedicated.webp",
            squareArtworkUri = null,
            logoUri = null,
            episodes = emptyList(),
        )
        val categories = listOf(
            ServusCategory(
                id = "NEWS",
                title = "News & Magazine",
                order = 0,
                shows = listOf(
                    ServusShow(
                        id = "WEATHER-MAIN",
                        title = "Servus Wetter",
                        description = null,
                        categoryId = "NEWS",
                        categoryTitle = "News & Magazine",
                        artworkUri = null,
                        squareArtworkUri = null,
                        logoUri = "https://example.invalid/weather-logo.webp",
                        episodes = emptyList(),
                    ),
                    existing,
                ),
            ),
        )

        val result = ServusCatalogAugmentation.withEditorialShows(categories, emptyList())
        val matches = result.single().shows.filter { it.id == ServusBranding.WEATHER_90_SECONDS_SHOW_ID }

        assertEquals(1, matches.size)
        assertEquals(ServusBranding.WEATHER_90_SECONDS_SHOW_NAME, matches.single().title)
        assertNotNull(matches.single().description)
        assertEquals("https://example.invalid/dedicated.webp", matches.single().artworkUri)
    }
}
