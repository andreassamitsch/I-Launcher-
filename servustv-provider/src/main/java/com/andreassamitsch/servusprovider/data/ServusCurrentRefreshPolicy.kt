package com.andreassamitsch.servusprovider.data

internal data class ServusCurrentCandidatePlan(
    val cachedEpisodes: List<ServusNewsEpisode>,
    val idsToLoad: List<String>,
)

/**
 * Keeps the frequent Aktuelles refresh incremental. Candidate discovery still comes from the live
 * ServusTV API, but immutable product details are only hydrated for content IDs we have not already
 * accepted and stored locally.
 */
internal object ServusCurrentRefreshPolicy {
    fun plan(
        candidateIds: List<String>,
        cachedEpisodes: List<ServusNewsEpisode>,
    ): ServusCurrentCandidatePlan {
        val distinctIds = candidateIds.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val cachedById = cachedEpisodes.associateBy { it.id }
        return ServusCurrentCandidatePlan(
            cachedEpisodes = distinctIds.mapNotNull(cachedById::get),
            idsToLoad = distinctIds.filterNot(cachedById::containsKey),
        )
    }
}
