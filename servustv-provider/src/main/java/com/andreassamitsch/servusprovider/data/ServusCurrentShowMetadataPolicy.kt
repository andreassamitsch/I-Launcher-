package com.andreassamitsch.servusprovider.data

import java.util.Locale

/**
 * Adds show identity and logo metadata to the lightweight Aktuelles feed without another API call.
 */
object ServusCurrentShowMetadataPolicy {
    fun enrich(
        episodes: List<ServusNewsEpisode>,
        categories: List<ServusCategory>,
    ): List<ServusNewsEpisode> {
        val shows = categories.flatMap { it.shows }.distinctBy { it.id }
        if (shows.isEmpty()) return ServusCurrentChannelPolicy.applyCanonicalBranding(episodes)

        return episodes.map { episode ->
            val kind = ServusNewsPolicy.contentKind(episode)
                ?: return@map episode.copy(
                    logoUri = ServusBranding.logoUriForEpisode(episode, episode.logoUri),
                )
            val show = shows
                .map { candidate -> candidate to matchScore(kind, candidate.title) }
                .filter { (_, score) -> score > 0 }
                .maxByOrNull { (_, score) -> score }
                ?.first
                ?: return@map episode.copy(
                    logoUri = ServusBranding.logoUriForEpisode(episode, episode.logoUri),
                )

            // For supported formats the content kind is stronger evidence than stale cached show
            // metadata. In particular a 90-second episode must never retain the generic news show
            // ID/logo merely because an older cache already populated those fields.
            val enriched = episode.copy(
                showId = show.id,
                showName = show.title,
                logoUri = episode.logoUri?.takeIf { it.isNotBlank() } ?: show.logoUri,
            )
            enriched.copy(
                logoUri = ServusBranding.logoUriForEpisode(enriched, enriched.logoUri),
            )
        }
    }

    private fun matchScore(kind: ServusContentKind, title: String): Int {
        val normalized = normalize(title)
        return when (kind) {
            ServusContentKind.FULL_NEWS -> when {
                "90 sekunden" in normalized || "90-sekunden" in normalized -> 0
                "nachrichten" !in normalized -> 0
                "19:20" in normalized -> 300
                "servus nachrichten" in normalized -> 200
                else -> 100
            }
            ServusContentKind.NEWS_90_SECONDS -> when {
                "nachrichten" !in normalized -> 0
                "90 sekunden" in normalized || "90-sekunden" in normalized -> 300
                else -> 0
            }
            ServusContentKind.WEGSCHEIDER -> if ("wegscheider" in normalized) 300 else 0
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""\s+"""), " ")
        .trim()
}
