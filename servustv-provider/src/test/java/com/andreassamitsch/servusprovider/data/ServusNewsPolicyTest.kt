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
        val episode = ServusNewsPolicy.toSupportedEpisode(
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
        assertEquals(ServusContentKind.FULL_NEWS, episode?.let(ServusNewsPolicy::contentKind))
        assertTrue(episode?.artworkUri?.contains("f_webp") == true)
    }

    @Test
    fun ninetySecondEditionIsAcceptedButNormalNewsClipIsNot() {
        val shortEdition = ServusNewsPolicy.toSupportedEpisode(
            ServusCardDto(
                id = "AA90",
                type = "video",
                title = "Tödlicher Schiffsbrand im Inselparadies",
                showName = "Servus Nachrichten in 90 Sekunden",
                duration = 100_000L,
                playable = true,
                sunriseTimestamp = "2026-08-30T10:00:00Z",
            ),
        )
        val normalClip = ServusNewsPolicy.toSupportedEpisode(
            ServusCardDto(
                id = "AACLIP",
                type = "video",
                title = "Mehrere Tote bei Schiffsunglück | Servus Nachrichten",
                showName = "Servus Nachrichten",
                duration = 120_000L,
                playable = true,
                sunriseTimestamp = "2026-08-30T10:05:00Z",
            ),
        )

        assertNotNull(shortEdition)
        assertEquals(ServusContentKind.NEWS_90_SECONDS, shortEdition?.let(ServusNewsPolicy::contentKind))
        assertNull(normalClip)
    }

    @Test
    fun wegscheiderIsAccepted() {
        val episode = ServusNewsPolicy.toSupportedEpisode(
            ServusCardDto(
                id = "AAWEG",
                type = "video",
                title = "Schwächlinge an die Macht!",
                showName = "Der Wegscheider",
                duration = 9 * 60 * 1000L,
                playable = true,
                sunriseTimestamp = "2026-08-29T17:26:00Z",
            ),
        )

        assertNotNull(episode)
        assertEquals(ServusContentKind.WEGSCHEIDER, episode?.let(ServusNewsPolicy::contentKind))
        assertEquals("Der Wegscheider", episode?.let(ServusNewsPolicy::displayLabel))
    }

    @Test
    fun sameFullEditionWithDifferentContentIdsIsPublishedOnlyOnce() {
        val shorter = episode(
            id = "AA-DUPLICATE-1",
            title = "Nachrichten 19:20 | 30.08.",
            showName = "Servus Nachrichten",
            durationMillis = 10 * 60 * 1000L,
            publishedAtMillis = instant("2026-08-30T17:20:00Z"),
        )
        val preferred = episode(
            id = "AA-DUPLICATE-2",
            title = "Servus Nachrichten 19:20 | 30.08.",
            showName = "Servus Nachrichten",
            durationMillis = 12 * 60 * 1000L,
            publishedAtMillis = instant("2026-08-30T17:20:00Z"),
        )

        val result = ServusNewsPolicy.deduplicateEpisodes(listOf(shorter, preferred))

        assertEquals(1, result.size)
        assertEquals("AA-DUPLICATE-2", result.single().id)
    }

    @Test
    fun multipleNinetySecondUpdatesOnSameDayRemainChronological() {
        val morning = episode(
            id = "AA-90-09",
            title = "Morgenlage",
            showName = "Servus Nachrichten in 90 Sekunden",
            durationMillis = 90_000L,
            publishedAtMillis = instant("2026-08-30T07:00:00Z"),
        )
        val noon = episode(
            id = "AA-90-12",
            title = "Mittagslage",
            showName = "Servus Nachrichten in 90 Sekunden",
            durationMillis = 100_000L,
            publishedAtMillis = instant("2026-08-30T10:00:00Z"),
        )
        val full = episode(
            id = "AA-FULL",
            title = "Nachrichten 19:20 | 30.08.",
            showName = "Servus Nachrichten",
            durationMillis = 12 * 60 * 1000L,
            publishedAtMillis = instant("2026-08-30T17:20:00Z"),
        )

        val result = ServusNewsPolicy.deduplicateEpisodes(listOf(morning, full, noon))

        assertEquals(listOf("AA-FULL", "AA-90-12", "AA-90-09"), result.map { it.id })
    }

    @Test
    fun duplicateNinetySecondItemWithDifferentIdsIsCollapsed() {
        val first = episode(
            id = "AA-90-DUP-1",
            title = "Tödlicher Schiffsbrand im Inselparadies",
            showName = "Servus Nachrichten in 90 Sekunden",
            durationMillis = 90_000L,
            publishedAtMillis = instant("2026-08-30T10:00:00Z"),
        )
        val second = first.copy(id = "AA-90-DUP-2", durationMillis = 100_000L)

        val result = ServusNewsPolicy.deduplicateEpisodes(listOf(first, second))

        assertEquals(1, result.size)
        assertEquals("AA-90-DUP-2", result.single().id)
    }

    @Test
    fun visibleDateAndTimeAreUsedWhenSunriseTimestampIsMissing() {
        val episode = ServusNewsPolicy.toSupportedEpisode(
            ServusCardDto(
                id = "AA90FALLBACK",
                title = "Aktuelle Meldungen",
                showName = "Servus Nachrichten in 90 Sekunden",
                shortDescription = "30.08. - 12:00 Uhr mit diesen Themen: Test",
                duration = 100_000L,
                playable = true,
            ),
            nowMillis = instant("2026-08-31T12:00:00Z"),
        )

        assertNotNull(episode)
        val expectedLocal = java.time.LocalDate.of(2026, 8, 30)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedLocal, episode?.publishedAtMillis)
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
                    "rbtv_display_art_landscape": { "type": "image" },
                    "rbtv_display_art_portrait": { "type": "image" }
                  }
                }
              ]
            }
            """.trimIndent(),
            SearchResponseDto::class.java,
        )

        val card = response.cards.single()
        assertTrue(card.mediaResources.contains("rbtv_display_art_landscape"))
        assertNotNull(ServusNewsPolicy.toSupportedEpisode(card))
    }

    @Test
    fun ninetySecondLabelDoesNotLeakIntoFull1920Format() {
        val episode = ServusNewsPolicy.toFullNewsEpisode(
            ServusCardDto(
                id = "AA125",
                type = "video",
                title = "Nachrichten 19:20 - 90 Sekunden",
                showName = "Servus Nachrichten in 90 Sekunden",
                duration = 90_000L,
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

    private fun episode(
        id: String,
        title: String,
        showName: String,
        durationMillis: Long,
        publishedAtMillis: Long,
    ) = ServusNewsEpisode(
        id = id,
        title = title,
        showName = showName,
        description = null,
        durationMillis = durationMillis,
        publishedAtMillis = publishedAtMillis,
        artworkUri = null,
    )

    private fun instant(value: String): Long = java.time.Instant.parse(value).toEpochMilli()
}
