package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusNewsPolicyTest {
    @Test
    fun full1920EditionIsAccepted() {
        val episode = ServusNewsPolicy.toFullNewsEpisode(
            ServusCardDto(
                id = "AA123",
                type = "video",
                contentType = "episode",
                title = "Nachrichten 19:20 | 30.08.",
                showName = "Servus Nachrichten",
                duration = 12 * 60 * 1000L,
                playable = true,
                sunriseTimestamp = "2026-08-30T17:20:00Z",
                mediaResources = listOf("rbtv_display_art_landscape"),
            ),
        )

        assertNotNull(episode)
        assertEquals("AA123", episode?.id)
        assertTrue(episode?.artworkUri?.contains("rbtv_display_art_landscape") == true)
    }

    @Test
    fun mediaResourcesObjectReturnedByApiIsNormalised() {
        val response = Gson().fromJson(
            """
            {
              "cards": [
                {
                  "id": "AA126",
                  "type": "video",
                  "content_type": "episode",
                  "title": "Nachrichten 19:20 | 31.08.",
                  "show_name": "Servus Nachrichten",
                  "duration": 720000,
                  "playable": true,
                  "media_resources": {
                    "rbtv_display_art_landscape": {
                      "type": "image"
                    },
                    "rbtv_display_art_portrait": {
                      "type": "image"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
            SearchResponseDto::class.java,
        )

        val card = response.cards.single()
        assertTrue(card.mediaResources.contains("rbtv_display_art_landscape"))
        assertNotNull(ServusNewsPolicy.toFullNewsEpisode(card))
    }

    @Test
    fun mediaResourcesArrayRemainsSupported() {
        val response = Gson().fromJson(
            """
            {
              "cards": [
                {
                  "id": "AA127",
                  "media_resources": ["rbtv_display_art_landscape", "rbtv_display_art_portrait"]
                }
              ]
            }
            """.trimIndent(),
            SearchResponseDto::class.java,
        )

        assertEquals(
            listOf("rbtv_display_art_landscape", "rbtv_display_art_portrait"),
            response.cards.single().mediaResources,
        )
    }

    @Test
    fun shortNewsClipIsRejected() {
        val episode = ServusNewsPolicy.toFullNewsEpisode(
            ServusCardDto(
                id = "AA124",
                type = "video",
                title = "Nachrichten 19:20 - Kurzmeldung",
                showName = "Servus Nachrichten",
                duration = 95_000L,
                playable = true,
            ),
        )

        assertNull(episode)
    }

    @Test
    fun ninetySecondEditionIsRejectedEvenWhenTitleContains1920() {
        val episode = ServusNewsPolicy.toFullNewsEpisode(
            ServusCardDto(
                id = "AA125",
                type = "video",
                title = "Nachrichten 19:20 - 90 Sekunden",
                showName = "Servus Nachrichten",
                duration = 10 * 60 * 1000L,
                playable = true,
            ),
        )

        assertNull(episode)
    }

    @Test
    fun vodUrlMatchesKodiAddonPattern() {
        assertEquals(
            "https://dms.redbull.tv/v5/CONTENT/TOKEN/playlist.m3u8?namespace=stv",
            ServusPlaybackResolver.buildVodUrl("CONTENT", "TOKEN"),
        )
    }
}
