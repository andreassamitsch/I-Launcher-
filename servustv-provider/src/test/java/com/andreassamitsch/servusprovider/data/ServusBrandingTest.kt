package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun ninetySecondLogoUsesCrossProcessContentUri() {
        assertEquals(
            "content://com.andreassamitsch.servusprovider.branding/servus_news_90_logo.png",
            ServusBranding.NEWS_90_SECONDS_LOGO_URI,
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
    fun canonicalizationRepairsLegacyNinetySecondEpisodeWithoutLogo() {
        val repaired = ServusBranding.canonicalizeEpisode(
            ServusNewsEpisode(
                id = "NEWS90-CACHED",
                title = "Attersee-Obduktionsergebnis ist da",
                showName = ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
                description = null,
                durationMillis = 90_000L,
                publishedAtMillis = null,
                artworkUri = null,
                showId = null,
                logoUri = null,
            ),
        )

        assertEquals(ServusContentKind.NEWS_90_SECONDS, repaired.contentKindHint)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, repaired.showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_NAME, repaired.showName)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, repaired.logoUri)
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

    @Test
    fun brandingTransformsAreCanonicalFitWithoutChangingArtwork() {
        val croppedLogo =
            "https://resources.redbull.tv/WEATHER/rbtv_title_treatment/f_webp,c_fill,h_180,q_75?namespace=stv&refresh=true"
        val heightOnlyLogo =
            "https://resources.redbull.tv/WEATHER/rbtv_wordmark/f_webp,h_180,q_80?namespace=stv&refresh=true"
        val artwork =
            "https://resources.redbull.tv/WEATHER/rbtv_display_art_landscape/f_webp,c_fill,w_1280,q_72?namespace=stv&refresh=true"
        val expectedTreatment =
            "https://resources.redbull.tv/WEATHER/rbtv_title_treatment/f_webp,c_fit,w_720,h_220,q_85?namespace=stv&refresh=true"
        val expectedWordmark =
            "https://resources.redbull.tv/WEATHER/rbtv_wordmark/f_webp,c_fit,w_720,h_220,q_85?namespace=stv&refresh=true"

        assertEquals(expectedTreatment, ServusBranding.normalizeLogoUri(croppedLogo))
        assertEquals(expectedWordmark, ServusBranding.normalizeLogoUri(heightOnlyLogo))
        assertEquals(artwork, ServusBranding.normalizeLogoUri(artwork))
    }

    @Test
    fun missingGenericShowLogoUsesResolvableLazyMarker() {
        val uri = ServusBranding.catalogueLogoUriForShow("SHOW-123", null)

        assertTrue(ServusBranding.isLazyLogoUri(uri))
        assertEquals("SHOW-123", ServusBranding.showIdFromLazyLogoUri(uri))
        assertFalse(ServusBranding.isLazyLogoUri("https://resources.redbull.tv/logo.webp"))
    }
}
