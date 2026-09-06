package com.andreassamitsch.servusprovider.data

/**
 * Canonical show identity/branding for editorial formats whose ServusTV API structure is known.
 *
 * These values are deliberately independent from the mutable catalogue cache. The generic news
 * product exposes a stable `rbtv_title_treatment`. The 90-second news show has no title-treatment in
 * the API, so its verified bundled logo is used locally. `NEWS_90_SECONDS_LOGO_URI` is the stable
 * transport marker written to Android TvProvider; ServusTV itself renders the bundled drawable
 * directly and must never depend on resolving that cross-process URI for its own UI.
 */
object ServusBranding {
    const val NEWS_SHOW_ID = "AA-1Y5RJCD1H2111"
    const val NEWS_SHOW_NAME = "Servus Nachrichten"
    const val NEWS_90_SECONDS_SHOW_ID = "AAYGF2URW6ALQYE42IJK"
    const val NEWS_90_SECONDS_SHOW_NAME = "Servus Nachrichten in 90 Sekunden"

    /** Dedicated ServusTV-On product which is not reliably present in the generic Sendungen rail. */
    const val WEATHER_90_SECONDS_SHOW_ID = "AA90VBHT0KRB2CMU1AHQ"
    const val WEATHER_90_SECONDS_SHOW_NAME = "Servus Wetter in 90 Sekunden"
    const val WEATHER_90_SECONDS_DESCRIPTION =
        "Das Servus Wetter in 90 Sekunden: Ab 6:00 Uhr mehrmals täglich bei ServusTV On!"

    const val NEWS_LOGO_URI =
        "https://resources.redbull.tv/AA-1Y5RJCD1H2111/rbtv_title_treatment/f_webp,h_180,q_80?namespace=stv&refresh=true"
    const val NEWS_90_SECONDS_LOGO_URI =
        "content://com.andreassamitsch.servusprovider.branding/servus_news_90_logo.png"
    const val NEWS_90_SECONDS_LEGACY_RESOURCE_URI =
        "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo"

    private const val LAZY_LOGO_PREFIX = "iservus-branding://show/"
    private const val ARTWORK_HOST_PREFIX = "https://resources.redbull.tv/"

    fun isNinetySecondLogoUri(uri: String?): Boolean =
        uri == NEWS_90_SECONDS_LOGO_URI || uri == NEWS_90_SECONDS_LEGACY_RESOURCE_URI

    /**
     * Canonicalises a resolved logo URI. Older development builds requested title treatments with
     * the CDN crop transform `c_fill`; that crop is destructive for transparent wordmarks and can
     * visibly cut off tall scripts such as Servus Wetter. Strip the crop only for actual branding
     * resources, never for normal artwork.
     */
    fun normalizeLogoUri(uri: String?): String? {
        val value = uri?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!value.startsWith(ARTWORK_HOST_PREFIX, ignoreCase = true)) return value
        if (!looksLikeBrandingResource(value)) return value
        return value
            .replace(",c_fill", "", ignoreCase = true)
            .replace("%2Cc_fill", "", ignoreCase = true)
    }

    fun logoUriForShow(showId: String?, fallback: String?): String? = when (showId) {
        NEWS_SHOW_ID -> NEWS_LOGO_URI
        NEWS_90_SECONDS_SHOW_ID -> NEWS_90_SECONDS_LOGO_URI
        else -> normalizeLogoUri(fallback)
    }

    /**
     * Catalogue cards are Local First. If the lightweight catalogue card has no logo metadata, keep
     * a stable lazy marker instead of guessing a resource filename. The artwork loader resolves that
     * marker from the official product detail only when the card becomes visible.
     */
    fun catalogueLogoUriForShow(showId: String?, fallback: String?): String? =
        logoUriForShow(showId, fallback)
            ?: showId?.trim()?.takeIf { it.isNotBlank() }?.let(::lazyLogoUri)

    fun isLazyLogoUri(uri: String?): Boolean = uri?.startsWith(LAZY_LOGO_PREFIX) == true

    fun showIdFromLazyLogoUri(uri: String?): String? = uri
        ?.takeIf(::isLazyLogoUri)
        ?.removePrefix(LAZY_LOGO_PREFIX)
        ?.takeIf { it.isNotBlank() }

    private fun lazyLogoUri(showId: String): String = "$LAZY_LOGO_PREFIX$showId"

    private fun looksLikeBrandingResource(uri: String): Boolean =
        uri.contains("title_treatment", ignoreCase = true) ||
            uri.contains("title-treatment", ignoreCase = true) ||
            uri.contains("treatment", ignoreCase = true) ||
            uri.contains("wordmark", ignoreCase = true) ||
            uri.contains("_logo", ignoreCase = true) ||
            uri.contains("/logo", ignoreCase = true)

    fun logoUriForEpisode(episode: ServusNewsEpisode, fallback: String?): String? = when {
        ServusNewsPolicy.contentKind(episode) == ServusContentKind.NEWS_90_SECONDS ->
            NEWS_90_SECONDS_LOGO_URI
        ServusNewsPolicy.contentKind(episode) == ServusContentKind.FULL_NEWS -> NEWS_LOGO_URI
        episode.showId == NEWS_90_SECONDS_SHOW_ID -> NEWS_90_SECONDS_LOGO_URI
        episode.showId == NEWS_SHOW_ID -> NEWS_LOGO_URI
        else -> normalizeLogoUri(fallback)
    }

    /**
     * Applies identity and logo together. Callers must never change only the logo for a known news
     * format: show ID, show name, format hint and logo are one atomic editorial identity.
     */
    fun canonicalizeEpisode(episode: ServusNewsEpisode): ServusNewsEpisode {
        return when (ServusNewsPolicy.contentKind(episode)) {
            ServusContentKind.FULL_NEWS -> episode.copy(
                showId = NEWS_SHOW_ID,
                showName = NEWS_SHOW_NAME,
                logoUri = NEWS_LOGO_URI,
                contentKindHint = ServusContentKind.FULL_NEWS,
            )
            ServusContentKind.NEWS_90_SECONDS -> episode.copy(
                showId = NEWS_90_SECONDS_SHOW_ID,
                showName = NEWS_90_SECONDS_SHOW_NAME,
                logoUri = NEWS_90_SECONDS_LOGO_URI,
                contentKindHint = ServusContentKind.NEWS_90_SECONDS,
            )
            ServusContentKind.WEGSCHEIDER, null -> episode.copy(
                logoUri = logoUriForEpisode(episode, episode.logoUri),
            )
        }
    }
}
