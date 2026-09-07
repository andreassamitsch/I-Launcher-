package com.andreassamitsch.servusprovider.data

/**
 * Canonical show identity/branding for editorial formats whose ServusTV API structure is known.
 *
 * Official API title treatments always win. `Servus Nachrichten in 90 Sekunden` deliberately keeps
 * the verified bundled logo as a local fallback because its dedicated product currently exposes no
 * title treatment. The fallback is therefore stable without preventing a future official asset from
 * taking over automatically.
 */
object ServusBranding {
    const val NEWS_SHOW_ID = "AA-1Y5RJCD1H2111"
    const val NEWS_SHOW_NAME = "Servus Nachrichten"
    const val NEWS_90_SECONDS_SHOW_ID = "AAYGF2URW6ALQYE42IJK"
    const val NEWS_90_SECONDS_SHOW_NAME = "Servus Nachrichten in 90 Sekunden"

    const val WEATHER_90_SECONDS_SHOW_ID = "AA90VBHT0KRB2CMU1AHQ"
    const val WEATHER_90_SECONDS_SHOW_NAME = "Servus Wetter in 90 Sekunden"
    const val WEATHER_90_SECONDS_DESCRIPTION =
        "Das Servus Wetter in 90 Sekunden: Ab 6:00 Uhr mehrmals täglich bei ServusTV On!"

    const val FLEISCHHACKER_SHOW_ID = "AA95DDIZGB942P3W94TM"
    const val FLEISCHHACKER_SHOW_NAME = "Der Servus Kommentar von Michael Fleischhacker"

    const val NEWS_LOGO_URI =
        "https://resources.redbull.tv/AA-1Y5RJCD1H2111/rbtv_title_treatment/f_webp,c_fit,w_720,h_220,q_85?namespace=stv&refresh=true"
    const val NEWS_90_SECONDS_LOGO_URI =
        "content://com.andreassamitsch.servusprovider.branding/servus_news_90_logo.png"
    const val NEWS_90_SECONDS_LEGACY_RESOURCE_URI =
        "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo"

    private const val LAZY_LOGO_PREFIX = "iservus-branding://show/"
    private const val ARTWORK_HOST_PREFIX = "https://resources.redbull.tv/"
    private val brandingTransformPattern = Regex("/f_[^?]+(?=\\?)", RegexOption.IGNORE_CASE)

    fun isNinetySecondLogoUri(uri: String?): Boolean =
        uri == NEWS_90_SECONDS_LOGO_URI || uri == NEWS_90_SECONDS_LEGACY_RESOURCE_URI

    fun normalizeLogoUri(uri: String?): String? {
        val value = uri?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!value.startsWith(ARTWORK_HOST_PREFIX, ignoreCase = true)) return value
        if (!looksLikeBrandingResource(value)) return value

        return if (brandingTransformPattern.containsMatchIn(value)) {
            brandingTransformPattern.replace(value, "/f_webp,c_fit,w_720,h_220,q_85")
        } else {
            value
        }
    }

    fun logoUriForShow(showId: String?, official: String?): String? = when (showId) {
        NEWS_SHOW_ID -> normalizeLogoUri(official) ?: NEWS_LOGO_URI
        NEWS_90_SECONDS_SHOW_ID -> normalizeLogoUri(official) ?: NEWS_90_SECONDS_LOGO_URI
        else -> normalizeLogoUri(official)
    }

    fun catalogueLogoUriForShow(showId: String?, official: String?): String? =
        logoUriForShow(showId, official)
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
            logoUriForShow(NEWS_90_SECONDS_SHOW_ID, fallback ?: episode.logoUri)
        ServusNewsPolicy.contentKind(episode) == ServusContentKind.FULL_NEWS ->
            logoUriForShow(NEWS_SHOW_ID, fallback ?: episode.logoUri)
        episode.showId == NEWS_90_SECONDS_SHOW_ID ->
            logoUriForShow(NEWS_90_SECONDS_SHOW_ID, fallback ?: episode.logoUri)
        episode.showId == NEWS_SHOW_ID -> logoUriForShow(NEWS_SHOW_ID, fallback ?: episode.logoUri)
        else -> normalizeLogoUri(fallback)
    }

    fun canonicalizeEpisode(episode: ServusNewsEpisode): ServusNewsEpisode {
        return when (ServusNewsPolicy.contentKind(episode)) {
            ServusContentKind.FULL_NEWS -> episode.copy(
                showId = NEWS_SHOW_ID,
                showName = NEWS_SHOW_NAME,
                logoUri = logoUriForShow(NEWS_SHOW_ID, episode.logoUri),
                contentKindHint = ServusContentKind.FULL_NEWS,
            )
            ServusContentKind.NEWS_90_SECONDS -> episode.copy(
                showId = NEWS_90_SECONDS_SHOW_ID,
                showName = NEWS_90_SECONDS_SHOW_NAME,
                logoUri = logoUriForShow(NEWS_90_SECONDS_SHOW_ID, episode.logoUri),
                contentKindHint = ServusContentKind.NEWS_90_SECONDS,
            )
            ServusContentKind.WEGSCHEIDER, null -> episode.copy(
                logoUri = logoUriForEpisode(episode, episode.logoUri),
            )
        }
    }
}
