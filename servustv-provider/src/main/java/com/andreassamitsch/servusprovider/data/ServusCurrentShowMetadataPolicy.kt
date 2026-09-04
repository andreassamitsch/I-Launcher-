package com.andreassamitsch.servusprovider.data

import java.util.Locale

/**
 * Adds stable show identity and branding to the lightweight Aktuelles feed without another API call.
 * Known news formats are canonicalized atomically; they are never re-parented merely because a
 * mutable catalogue cache happens to contain a generic show/logo.
 */
object ServusCurrentShowMetadataPolicy {
    fun enrich(
        episodes: List<ServusNewsEpisode>,
        categories: List<ServusCategory>,
    ): List<ServusNewsEpisode> {
        val shows = categories.flatMap { it.shows }.distinctBy { it.id }
        val dedicated90EpisodeIds = shows
            .firstOrNull { it.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID }
            ?.episodes
            .orEmpty()
            .mapTo(hashSetOf()) { it.id }

        return episodes.map { episode ->
            // Exact membership in the dedicated 90-second show repairs old dev34 caches where
            // opening the generic news show had already overwritten showId/showName/logo.
            val kind = if (episode.id in dedicated90EpisodeIds) {
                ServusContentKind.NEWS_90_SECONDS
            } else {
                ServusNewsPolicy.contentKind(episode)
            }

            if (kind == ServusContentKind.FULL_NEWS || kind == ServusContentKind.NEWS_90_SECONDS) {
                return@map ServusBranding.canonicalizeEpisode(
                    episode.copy(contentKindHint = kind),
                )
            }

            if (kind == null) {
                return@map ServusBranding.canonicalizeEpisode(episode)
            }

            val show = shows
                .map { candidate -> candidate to matchScore(kind, candidate.title) }
                .filter { (_, score) -> score > 0 }
                .maxByOrNull { (_, score) -> score }
                ?.first
                ?: return@map ServusBranding.canonicalizeEpisode(
                    episode.copy(contentKindHint = kind),
                )

            ServusBranding.canonicalizeEpisode(
                episode.copy(
                    showId = show.id,
                    showName = show.title,
                    logoUri = show.logoUri ?: episode.logoUri,
                    contentKindHint = kind,
                ),
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
