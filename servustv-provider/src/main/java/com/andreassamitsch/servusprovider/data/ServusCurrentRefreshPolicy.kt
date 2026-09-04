package com.andreassamitsch.servusprovider.data

internal data class ServusCurrentCandidate(
    val id: String,
    val contentKindHint: ServusContentKind? = null,
)

internal data class ServusCurrentCandidatePlan(
    val cachedEpisodes: List<ServusNewsEpisode>,
    val candidatesToLoad: List<ServusCurrentCandidate>,
) {
    val idsToLoad: List<String> get() = candidatesToLoad.map { it.id }
}

/**
 * Keeps the frequent Aktuelles refresh incremental. Candidate discovery still comes from the live
 * ServusTV API, but immutable product details are only hydrated for content IDs we have not already
 * accepted and stored locally. New source-collection hints are applied even to cached episodes so a
 * corrected editorial identity does not require throwing the cache away.
 */
internal object ServusCurrentRefreshPolicy {
    fun plan(
        candidateIds: List<String>,
        cachedEpisodes: List<ServusNewsEpisode>,
    ): ServusCurrentCandidatePlan = planCandidates(
        candidates = candidateIds.map { ServusCurrentCandidate(it) },
        cachedEpisodes = cachedEpisodes,
    )

    fun planCandidates(
        candidates: List<ServusCurrentCandidate>,
        cachedEpisodes: List<ServusNewsEpisode>,
    ): ServusCurrentCandidatePlan {
        val distinct = LinkedHashMap<String, ServusCurrentCandidate>()
        candidates.asSequence()
            .filter { it.id.isNotBlank() }
            .forEach { candidate ->
                val existing = distinct[candidate.id]
                if (existing == null || existing.contentKindHint == null && candidate.contentKindHint != null) {
                    distinct[candidate.id] = candidate
                }
            }

        val cachedById = cachedEpisodes.associateBy { it.id }
        val cached = distinct.values.mapNotNull { candidate ->
            val episode = cachedById[candidate.id] ?: return@mapNotNull null
            val withHint = candidate.contentKindHint?.let { hint ->
                episode.copy(contentKindHint = hint)
            } ?: episode
            ServusBranding.canonicalizeEpisode(withHint)
        }
        val toLoad = distinct.values.filterNot { cachedById.containsKey(it.id) }
        return ServusCurrentCandidatePlan(
            cachedEpisodes = cached,
            candidatesToLoad = toLoad,
        )
    }
}
