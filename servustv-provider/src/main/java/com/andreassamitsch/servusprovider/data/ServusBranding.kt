package com.andreassamitsch.servusprovider.data

/** Canonical show branding that must survive caches and Android TV publication. */
object ServusBranding {
    const val NEWS_90_SECONDS_SHOW_ID = "AAYGF2URW6ALQYE42IJK"
    const val NEWS_90_SECONDS_LOGO_URI =
        "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo"

    fun logoUriForShow(showId: String?, fallback: String?): String? =
        if (showId == NEWS_90_SECONDS_SHOW_ID) NEWS_90_SECONDS_LOGO_URI else fallback

    fun logoUriForEpisode(episode: ServusNewsEpisode, fallback: String?): String? =
        if (
            episode.showId == NEWS_90_SECONDS_SHOW_ID ||
            ServusNewsPolicy.contentKind(episode) == ServusContentKind.NEWS_90_SECONDS
        ) {
            NEWS_90_SECONDS_LOGO_URI
        } else {
            fallback
        }
}
