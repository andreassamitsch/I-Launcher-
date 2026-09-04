package com.andreassamitsch.servusprovider.data

/**
 * Canonical show identity/branding for the news formats whose ServusTV API structure is known.
 *
 * These values are deliberately independent from the mutable catalogue cache. The generic news
 * product exposes a stable `rbtv_title_treatment`, while the 90-second show needs the verified local
 * vector logo because the API currently exposes no title-treatment resource for that show.
 */
object ServusBranding {
    const val NEWS_SHOW_ID = "AA-1Y5RJCD1H2111"
    const val NEWS_SHOW_NAME = "Servus Nachrichten"
    const val NEWS_90_SECONDS_SHOW_ID = "AAYGF2URW6ALQYE42IJK"
    const val NEWS_90_SECONDS_SHOW_NAME = "Servus Nachrichten in 90 Sekunden"

    const val NEWS_LOGO_URI =
        "https://resources.redbull.tv/AA-1Y5RJCD1H2111/rbtv_title_treatment/f_webp,c_fill,h_180,q_75?namespace=stv&refresh=true"
    const val NEWS_90_SECONDS_LOGO_URI =
        "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo"

    fun logoUriForShow(showId: String?, fallback: String?): String? = when (showId) {
        NEWS_SHOW_ID -> NEWS_LOGO_URI
        NEWS_90_SECONDS_SHOW_ID -> NEWS_90_SECONDS_LOGO_URI
        else -> fallback
    }

    fun logoUriForEpisode(episode: ServusNewsEpisode, fallback: String?): String? = when {
        ServusNewsPolicy.contentKind(episode) == ServusContentKind.NEWS_90_SECONDS ->
            NEWS_90_SECONDS_LOGO_URI
        ServusNewsPolicy.contentKind(episode) == ServusContentKind.FULL_NEWS -> NEWS_LOGO_URI
        episode.showId == NEWS_90_SECONDS_SHOW_ID -> NEWS_90_SECONDS_LOGO_URI
        episode.showId == NEWS_SHOW_ID -> NEWS_LOGO_URI
        else -> fallback
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
