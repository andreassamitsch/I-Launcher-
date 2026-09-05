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

    @Test
    fun collectionHintRepairsCachedNinetySecondIdentityWithoutReloadingProduct() {
        val corrupted = ServusNewsEpisode(
            id = "AA0HN7PRG6IMJ12WPCB2",
            title = "Paukenschlag bei VW",
            showName = ServusBranding.NEWS_SHOW_NAME,
            description = null,
            durationMillis = 89_800L,
            publishedAtMillis = 1_000L,
            artworkUri = null,
            showId = ServusBranding.NEWS_SHOW_ID,
            logoUri = ServusBranding.NEWS_LOGO_URI,
        )

        val plan = ServusCurrentRefreshPolicy.planCandidates(
            candidates = listOf(
                ServusCurrentCandidate(
                    id = corrupted.id,
                    contentKindHint = ServusContentKind.NEWS_90_SECONDS,
                ),
            ),
            cachedEpisodes = listOf(corrupted),
        )

        assertEquals(emptyList<String>(), plan.idsToLoad)
        val repaired = plan.cachedEpisodes.single()
        assertEquals(ServusContentKind.NEWS_90_SECONDS, repaired.contentKindHint)
        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, repaired.showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, repaired.logoUri)
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
