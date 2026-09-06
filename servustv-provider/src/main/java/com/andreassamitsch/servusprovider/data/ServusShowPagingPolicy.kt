package com.andreassamitsch.servusprovider.data

import java.util.Locale

/** Pure paging helpers kept separate from Android/UI code so focus-triggered lazy loading is testable. */
internal object ServusShowPagingPolicy {
    const val PAGE_SIZE = 8
    const val PREFETCH_DISTANCE = 3

    fun shouldPrefetch(
        focusedIndex: Int,
        itemCount: Int,
        hasMore: Boolean,
        prefetchDistance: Int = PREFETCH_DISTANCE,
    ): Boolean {
        if (!hasMore || focusedIndex < 0 || itemCount <= 0) return false
        return focusedIndex >= (itemCount - prefetchDistance.coerceAtLeast(1)).coerceAtLeast(0)
    }

    /**
     * Freshly hydrated entries win over cached entries with the same ID. The result deliberately has
     * no fixed episode limit: Android-TV channels can still apply their own bounded selection, while
     * the in-app show page can progressively grow as the user reaches the end of the list.
     */
    fun mergeEpisodes(
        cached: List<ServusNewsEpisode>,
        fresh: List<ServusNewsEpisode>,
    ): List<ServusNewsEpisode> {
        val byId = LinkedHashMap<String, ServusNewsEpisode>()
        fresh.forEach { episode -> byId[episode.id] = episode }
        cached.forEach { episode -> byId.putIfAbsent(episode.id, episode) }

        return byId.values
            .groupBy(::editorialKey)
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<ServusNewsEpisode> { metadataScore(it) }
                        .thenBy { it.durationMillis },
                )
            }
            .sortedWith(
                compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE }
                    .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                    .thenByDescending { it.episodeNumber ?: Int.MIN_VALUE },
            )
    }

    private fun metadataScore(episode: ServusNewsEpisode): Int = buildList {
        if (!episode.description.isNullOrBlank()) add(1)
        if (episode.seasonNumber != null) add(1)
        if (episode.episodeNumber != null) add(1)
        if (!episode.artworkUri.isNullOrBlank()) add(1)
        if (episode.publishedAtMillis != null) add(1)
    }.size

    private fun editorialKey(episode: ServusNewsEpisode): String {
        val minute = ServusNewsPolicy.recencyMillis(episode)?.div(60_000L)?.toString() ?: "unknown"
        return "${episode.showId.orEmpty()}|$minute|${normalize(episode.title)}"
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), "-")
        .trim('-')
        .take(100)
}
