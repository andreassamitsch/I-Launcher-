package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusCardDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ServusCollectionPolicyTest {
    @Test
    fun recommendationRailIsNotContent() {
        val response = SearchResponseDto(
            id = "RELATED",
            label = "Das könnte Ihnen auch gefallen",
            cards = listOf(playableCard("OTHER", "Andere Sendung")),
        )

        assertEquals(
            ServusCollectionRole.RECOMMENDATION,
            ServusCollectionPolicy.classify(response, "SHOW", "Fleischhacker"),
        )
    }

    @Test
    fun playnetRailIsInfoEvenWithPlayableCards() {
        val response = SearchResponseDto(
            id = "PLAYNET",
            label = "Mehr zu Fleischhacker",
            listType = "playnet",
            cards = listOf(playableCard("SHOW", "Fleischhacker")),
        )

        assertEquals(
            ServusCollectionRole.INFO,
            ServusCollectionPolicy.classify(response, "SHOW", "Fleischhacker"),
        )
    }

    @Test
    fun apiPlaylistIdentityMarksEpisodeCollectionAsContent() {
        val response = SearchResponseDto(
            id = "EPISODES",
            label = "Aktuelle Sendungen",
            cards = listOf(
                ServusCardDto(
                    id = "EP1",
                    type = "video",
                    contentType = "episode",
                    title = "Folge 1",
                    playable = true,
                    deeplinkPlaylist = "SHOW:all_episodes",
                ),
                ServusCardDto(
                    id = "EP2",
                    type = "video",
                    contentType = "episode",
                    title = "Folge 2",
                    playable = true,
                    nextPlaylist = "SHOW:all_episodes",
                ),
            ),
        )

        assertEquals(
            ServusCollectionRole.CONTENT,
            ServusCollectionPolicy.classify(response, "SHOW", "Fleischhacker"),
        )
    }

    @Test
    fun ninetySecondChildCollectionGetsDedicatedStableParent() {
        val response = SearchResponseDto(
            id = "NEWS90-COLLECTION",
            label = "Servus Nachrichten in 90 Sekunden",
            cards = listOf(playableCard(null, "Servus Nachrichten in 90 Sekunden")),
        )

        assertEquals(
            ServusCollectionRole.CONTENT,
            ServusCollectionPolicy.classify(
                response,
                ServusBranding.NEWS_SHOW_ID,
                ServusBranding.NEWS_SHOW_NAME,
            ),
        )
        assertEquals(
            ServusBranding.NEWS_90_SECONDS_SHOW_ID,
            ServusCollectionPolicy.contentShowId(ServusBranding.NEWS_SHOW_ID, response),
        )
        assertEquals(
            ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
            ServusCollectionPolicy.contentShowTitle(ServusBranding.NEWS_SHOW_NAME, response),
        )
    }

    private fun playableCard(showId: String?, showName: String): ServusCardDto = ServusCardDto(
        id = "CARD-${showId ?: "CHILD"}",
        type = "video",
        contentType = "episode",
        title = "Folge",
        showName = showName,
        playable = true,
        deeplinkPlaylist = showId?.let { "$it:all_episodes" },
    )
}
