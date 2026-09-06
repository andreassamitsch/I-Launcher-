package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusCatalogAugmentationTest {
    @Test
    fun missingNinetySecondNewsShowIsInsertedBesideMainNewsShow() {
        val categories = listOf(
            ServusCategory(
                id = "CURRENT",
                title = "Aktuelles",
                order = 0,
                shows = listOf(mainNewsShow()),
            ),
        )
        val currentEpisodes = listOf(
            episode(
                id = "NEWS-90-NEW",
                kind = ServusContentKind.NEWS_90_SECONDS,
                publishedAtMillis = 2_000L,
                artworkUri = "https://example.invalid/90.webp",
            ),
            episode(
                id = "NEWS-FULL",
                kind = ServusContentKind.FULL_NEWS,
                publishedAtMillis = 3_000L,
            ),
        )

        val result = ServusCatalogAugmentation.withNinetySecondNewsShow(categories, currentEpisodes)
        val shows = result.single().shows

        assertEquals(
            listOf(ServusBranding.NEWS_SHOW_ID, ServusBranding.NEWS_90_SECONDS_SHOW_ID),
            shows.map { it.id },
        )
        val ninetySecondShow = shows.last()
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_NAME, ninetySecondShow.title)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, ninetySecondShow.logoUri)
        assertEquals("https://example.invalid/90.webp", ninetySecondShow.artworkUri)
        assertEquals(listOf("NEWS-90-NEW"), ninetySecondShow.episodes.map { it.id })
        assertTrue(ninetySecondShow.episodes.all { it.categoryId == "CURRENT" })
    }

    @Test
    fun existingNinetySecondNewsShowIsNotDuplicatedAndReceivesCurrentEpisodes() {
        val existing = ServusShow(
            id = ServusBranding.NEWS_90_SECONDS_SHOW_ID,
            title = "90 Sekunden",
            description = "Bestehende API-Metadaten",
            categoryId = "CURRENT",
            categoryTitle = "Aktuelles",
            artworkUri = null,
            squareArtworkUri = null,
            logoUri = null,
            episodes = listOf(
                episode(
                    id = "OLD",
                    kind = ServusContentKind.NEWS_90_SECONDS,
                    publishedAtMillis = 60_000L,
                ),
            ),
        )
        val categories = listOf(
            ServusCategory(
                id = "CURRENT",
                title = "Aktuelles",
                order = 0,
                shows = listOf(mainNewsShow(), existing),
            ),
        )

        val result = ServusCatalogAugmentation.withNinetySecondNewsShow(
            categories,
            listOf(
                episode(
                    id = "NEW",
                    kind = ServusContentKind.NEWS_90_SECONDS,
                    publishedAtMillis = 120_000L,
                    artworkUri = "https://example.invalid/new.webp",
                ),
            ),
        )
        val ninetySecondShows = result.single().shows.filter {
            it.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID
        }

        assertEquals(1, ninetySecondShows.size)
        val show = ninetySecondShows.single()
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_NAME, show.title)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, show.logoUri)
        assertEquals("https://example.invalid/new.webp", show.artworkUri)
        assertEquals(listOf("NEW", "OLD"), show.episodes.map { it.id })
        assertNotNull(show.description)
    }

    @Test
    fun catalogueWithoutMainNewsShowIsLeftUnchanged() {
        val categories = listOf(
            ServusCategory(
                id = "SPORT",
                title = "Sport",
                order = 0,
                shows = listOf(
                    ServusShow(
                        id = "SPORT-SHOW",
                        title = "Sport",
                        description = null,
                        categoryId = "SPORT",
                        categoryTitle = "Sport",
                        artworkUri = null,
                        squareArtworkUri = null,
                        logoUri = null,
                        episodes = emptyList(),
                    ),
                ),
            ),
        )

        assertEquals(
            categories,
            ServusCatalogAugmentation.withNinetySecondNewsShow(
                categories,
                listOf(episode("NEWS-90", ServusContentKind.NEWS_90_SECONDS, 1_000L)),
            ),
        )
    }

    private fun mainNewsShow() = ServusShow(
        id = ServusBranding.NEWS_SHOW_ID,
        title = ServusBranding.NEWS_SHOW_NAME,
        description = null,
        categoryId = "CURRENT",
        categoryTitle = "Aktuelles",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = ServusBranding.NEWS_LOGO_URI,
        episodes = emptyList(),
    )

    private fun episode(
        id: String,
        kind: ServusContentKind,
        publishedAtMillis: Long,
        artworkUri: String? = null,
    ) = ServusNewsEpisode(
        id = id,
        title = if (kind == ServusContentKind.NEWS_90_SECONDS) "Nachrichten kompakt" else "Nachrichten 19:20",
        showName = if (kind == ServusContentKind.NEWS_90_SECONDS) {
            ServusBranding.NEWS_90_SECONDS_SHOW_NAME
        } else {
            ServusBranding.NEWS_SHOW_NAME
        },
        description = null,
        durationMillis = if (kind == ServusContentKind.NEWS_90_SECONDS) 90_000L else 12 * 60_000L,
        publishedAtMillis = publishedAtMillis,
        artworkUri = artworkUri,
        showId = if (kind == ServusContentKind.NEWS_90_SECONDS) {
            ServusBranding.NEWS_90_SECONDS_SHOW_ID
        } else {
            ServusBranding.NEWS_SHOW_ID
        },
        contentType = if (kind == ServusContentKind.NEWS_90_SECONDS) "clip" else "episode",
        contentKindHint = kind,
    )
}
