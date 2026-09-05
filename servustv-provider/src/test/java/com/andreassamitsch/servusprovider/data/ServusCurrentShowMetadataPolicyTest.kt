package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServusCurrentShowMetadataPolicyTest {
    @Test
    fun enrichesSupportedCurrentEpisodesWithCanonicalNewsLogos() {
        val categories = listOf(
            ServusCategory(
                id = "news",
                title = "Nachrichten",
                order = 0,
                shows = listOf(
                    show(ServusBranding.NEWS_SHOW_ID, "Servus Nachrichten", "https://cdn/stale-full-logo.webp"),
                    show(ServusBranding.NEWS_90_SECONDS_SHOW_ID, "Servus Nachrichten in 90 Sekunden", "https://cdn/stale-short-logo.webp"),
                    show("weg", "Der Wegscheider", "https://cdn/weg-logo.webp"),
                ),
            ),
        )
        val episodes = listOf(
            episode("a", "Nachrichten 19:20 | 03.09.", "Servus Nachrichten", 15 * 60_000L),
            episode("b", "Servus Nachrichten in 90 Sekunden", "Servus Nachrichten in 90 Sekunden", 90_000L),
            episode("c", "Der Wegscheider", "Der Wegscheider", 8 * 60_000L),
        )

        val result = ServusCurrentShowMetadataPolicy.enrich(episodes, categories)

        assertEquals(ServusBranding.NEWS_SHOW_ID, result[0].showId)
        assertEquals(ServusBranding.NEWS_LOGO_URI, result[0].logoUri)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, result[1].showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, result[1].logoUri)
        assertEquals("weg", result[2].showId)
        assertEquals("https://cdn/weg-logo.webp", result[2].logoUri)
    }

    @Test
    fun ninetySecondMetadataOverridesStaleGenericNewsIdentityAndLogo() {
        val categories = listOf(
            ServusCategory(
                id = "news",
                title = "Nachrichten",
                order = 0,
                shows = listOf(
                    show(ServusBranding.NEWS_SHOW_ID, "Servus Nachrichten", ServusBranding.NEWS_LOGO_URI),
                    show(
                        ServusBranding.NEWS_90_SECONDS_SHOW_ID,
                        "Servus Nachrichten in 90 Sekunden",
                        ServusBranding.NEWS_90_SECONDS_LOGO_URI,
                    ),
                ),
            ),
        )
        val stale = episode(
            "b",
            "Frau in Graz getötet",
            "Servus Nachrichten in 90 Sekunden",
            90_000L,
        ).copy(
            showId = ServusBranding.NEWS_SHOW_ID,
            logoUri = ServusBranding.NEWS_LOGO_URI,
        )

        val result = ServusCurrentShowMetadataPolicy.enrich(listOf(stale), categories).single()

        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, result.showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, result.logoUri)
    }

    @Test
    fun dev34TopicalNinetySecondCacheIsRepairedFromDedicatedShowMembership() {
        val correctCatalogEpisode = episode(
            id = "AA0HN7PRG6IMJ12WPCB2",
            title = "Paukenschlag bei VW",
            showName = ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
            duration = 89_800L,
        ).copy(
            showId = ServusBranding.NEWS_90_SECONDS_SHOW_ID,
            logoUri = ServusBranding.NEWS_90_SECONDS_LOGO_URI,
            contentKindHint = ServusContentKind.NEWS_90_SECONDS,
        )
        val categories = listOf(
            ServusCategory(
                id = "news",
                title = "Nachrichten",
                order = 0,
                shows = listOf(
                    show(ServusBranding.NEWS_SHOW_ID, "Servus Nachrichten", ServusBranding.NEWS_LOGO_URI),
                    show(
                        ServusBranding.NEWS_90_SECONDS_SHOW_ID,
                        ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
                        ServusBranding.NEWS_90_SECONDS_LOGO_URI,
                        episodes = listOf(correctCatalogEpisode),
                    ),
                ),
            ),
        )
        // This is the broken state observed on the real device after opening Servus Nachrichten:
        // topical title, generic show identity, generic logo, no persisted format hint.
        val corrupted = correctCatalogEpisode.copy(
            showId = ServusBranding.NEWS_SHOW_ID,
            showName = ServusBranding.NEWS_SHOW_NAME,
            logoUri = ServusBranding.NEWS_LOGO_URI,
            contentKindHint = null,
        )

        val result = ServusCurrentShowMetadataPolicy.enrich(listOf(corrupted), categories).single()

        assertEquals(ServusContentKind.NEWS_90_SECONDS, result.contentKindHint)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, result.showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_NAME, result.showName)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, result.logoUri)
    }

    @Test
    fun fullNewsGetsCanonicalLogoEvenBeforeCatalogIsAvailable() {
        val source = episode("a", "Nachrichten 19:20 | 03.09.", "Servus Nachrichten", 15 * 60_000L)
        val result = ServusCurrentShowMetadataPolicy.enrich(listOf(source), emptyList()).single()

        assertEquals(ServusBranding.NEWS_SHOW_ID, result.showId)
        assertEquals(ServusBranding.NEWS_LOGO_URI, result.logoUri)
    }

    @Test
    fun unknownEpisodeRemainsWithoutInventedShowIdentity() {
        val source = episode("x", "Unbekannter Beitrag", "Unbekannte Sendung", 60_000L)
        val result = ServusCurrentShowMetadataPolicy.enrich(listOf(source), emptyList()).single()
        assertNull(result.showId)
        assertNull(result.logoUri)
    }

    private fun show(
        id: String,
        title: String,
        logo: String,
        episodes: List<ServusNewsEpisode> = emptyList(),
    ) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "news",
        categoryTitle = "Nachrichten",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = logo,
        episodes = episodes,
    )

    private fun episode(id: String, title: String, showName: String, duration: Long) = ServusNewsEpisode(
        id = id,
        title = title,
        showName = showName,
        description = null,
        durationMillis = duration,
        publishedAtMillis = null,
        artworkUri = null,
    )
}
