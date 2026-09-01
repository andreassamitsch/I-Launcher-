package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServusCurrentRefreshPolicyTest {
    @Test
    fun reusesCachedCandidatesAndLoadsOnlyUnknownIds() {
        val cached = episode("KNOWN")
        val unrelated = episode("OLD")

        val plan = ServusCurrentRefreshPolicy.plan(
            candidateIds = listOf("KNOWN", "NEW", "KNOWN", ""),
            cachedEpisodes = listOf(cached, unrelated),
        )

        assertEquals(listOf(cached), plan.cachedEpisodes)
        assertEquals(listOf("NEW"), plan.idsToLoad)
    }

    @Test
    fun preservesCandidateOrderForCachedAndUnknownItems() {
        val cachedA = episode("A")
        val cachedC = episode("C")

        val plan = ServusCurrentRefreshPolicy.plan(
            candidateIds = listOf("C", "B", "A", "D"),
            cachedEpisodes = listOf(cachedA, cachedC),
        )

        assertEquals(listOf(cachedC, cachedA), plan.cachedEpisodes)
        assertEquals(listOf("B", "D"), plan.idsToLoad)
    }

    private fun episode(id: String) = ServusNewsEpisode(
        id = id,
        title = id,
        showName = "Servus Nachrichten",
        description = null,
        durationMillis = 60_000L,
        publishedAtMillis = 1_000L,
        artworkUri = null,
    )
}
