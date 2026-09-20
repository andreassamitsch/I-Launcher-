package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusDirectCurrentPolicyTest {
    private val playable = ServusCardDto(id = "episode-1", title = "Folge", type = "video", playable = true)

    @Test fun newsPageUsesEditorialFormatLabels() {
        val showId = ServusBranding.NEWS_SHOW_ID
        assertEquals(ServusContentKind.NEWS_90_SECONDS,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten in 90 Sekunden", listOf(playable)).single().contentKindHint)
        assertEquals(ServusContentKind.FULL_NEWS,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten 19:20", listOf(playable)).single().contentKindHint)
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Servus Nachrichten: Einzelbeiträge", listOf(playable)).isEmpty())
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Das könnte Ihnen auch gefallen", listOf(playable)).isEmpty())
    }

    @Test fun separateWegscheiderFeedIgnoresRecommendations() {
        val showId = "AA-1Q66UK71N1W11"
        assertEquals(ServusContentKind.WEGSCHEIDER,
            ServusDirectCurrentPolicy.candidatesForCollection(showId, "Aktuelle Sendungen", listOf(playable)).single().contentKindHint)
        assertTrue(ServusDirectCurrentPolicy.candidatesForCollection(showId, "Das könnte Ihnen auch gefallen", listOf(playable)).isEmpty())
    }

    @Test fun excludesUnplayableInvalidAndDuplicateCards() {
        val cards = listOf(
            playable, playable,
            playable.copy(id = "hidden", playable = false),
            playable.copy(id = null),
            playable.copy(id = "navigation", type = "page", contentType = null),
        )
        assertEquals(listOf("episode-1"),
            ServusDirectCurrentPolicy.candidatesForCollection(ServusBranding.NEWS_SHOW_ID,
                "Servus Nachrichten 19:20", cards).map { it.id })
    }
}
