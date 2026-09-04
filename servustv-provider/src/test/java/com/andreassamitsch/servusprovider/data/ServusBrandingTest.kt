package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServusBrandingTest {
    @Test
    fun ninetySecondEpisodeOverridesWrongFallbackLogo() {
        val episode = ServusNewsEpisode(
            id = "NEWS90",
            title = "Servus Nachrichten in 90 Sekunden",
            showName = "Servus Nachrichten in 90 Sekunden",
            description = null,
            durationMillis = 90_000L,
            publishedAtMillis = null,
            artworkUri = null,
        )

        assertEquals(
            ServusBranding.NEWS_90_SECONDS_LOGO_URI,
            ServusBranding.logoUriForEpisode(episode, "https://wrong.example/logo.webp"),
        )
    }

    @Test
    fun exactNinetySecondShowIdOverridesAmbiguousEpisodeMetadata() {
        val episode = ServusNewsEpisode(
            id = "NEWS90-ID",
            title = "Aktuelle Meldungen",
            showName = "Servus Nachrichten",
            description = null,
            durationMillis = 90_000L,
            publishedAtMillis = null,
            artworkUri = null,
            showId = ServusBranding.NEWS_90_SECONDS_SHOW_ID,
        )

        assertEquals(
            ServusBranding.NEWS_90_SECONDS_LOGO_URI,
            ServusBranding.logoUriForEpisode(episode, null),
        )
    }

    @Test
    fun fullNewsAlwaysUsesCanonicalApiTitleTreatment() {
        val episode = ServusNewsEpisode(
            id = "FULL",
            title = "Servus Nachrichten 19:20",
            showName = "Servus Nachrichten",
            description = null,
            durationMillis = 600_000L,
            publishedAtMillis = null,
            artworkUri = null,
        )

        assertEquals(
            ServusBranding.NEWS_LOGO_URI,
            ServusBranding.logoUriForEpisode(episode, "https://wrong.example/full-news-logo.webp"),
        )
    }

    @Test
    fun canonicalizationRepairsFullNewsIdNameAndLogoTogether() {
        val repaired = ServusBranding.canonicalizeEpisode(
            ServusNewsEpisode(
                id = "FULL",
                title = "Nachrichten 19:20 | 03.09.",
                showName = null,
                description = null,
                durationMillis = 800_000L,
                publishedAtMillis = null,
                artworkUri = null,
                logoUri = null,
            ),
        )

        assertEquals(ServusContentKind.FULL_NEWS, repaired.contentKindHint)
        assertEquals(ServusBranding.NEWS_SHOW_ID, repaired.showId)
        assertEquals(ServusBranding.NEWS_SHOW_NAME, repaired.showName)
        assertEquals(ServusBranding.NEWS_LOGO_URI, repaired.logoUri)
    }
}
