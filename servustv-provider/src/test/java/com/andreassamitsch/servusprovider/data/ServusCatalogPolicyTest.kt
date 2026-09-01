package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ServusCatalogPolicyTest {
    @Test
    fun sendungenCollectionMetadataIsParsed() {
        val response = Gson().fromJson(
            """
            {
              "id":"category-news",
              "label":"News & Magazine",
              "list_type":"standard",
              "cards":[]
            }
            """.trimIndent(),
            SearchResponseDto::class.java,
        )
        assertEquals("category-news", response.id)
        assertEquals("News & Magazine", response.label)
        assertEquals("standard", response.listType)
    }

    @Test
    fun onlyForbiddenCategoryCanBeSkipped() {
        assertTrue(ServusCatalogPolicy.canSkipCategoryHttpCode(403))
        assertFalse(ServusCatalogPolicy.canSkipCategoryHttpCode(401))
        assertFalse(ServusCatalogPolicy.canSkipCategoryHttpCode(404))
        assertFalse(ServusCatalogPolicy.canSkipCategoryHttpCode(500))
    }

    @Test
    fun titleTreatmentIsExposedAsLogo() {
        val uri = ServusCatalogPolicy.titleTreatment(
            "SHOW-ID",
            listOf("rbtv_display_art_landscape", "rbtv_title_treatment"),
        )
        assertNotNull(uri)
        assertTrue(uri!!.contains("SHOW-ID/rbtv_title_treatment"))
        assertTrue(uri.contains("f_webp"))
    }

    @Test
    fun unrelatedNewsCardIsNotAttachedToServusWetter() {
        val card = ServusCardDto(
            id = "NEWS",
            type = "video",
            contentType = "episode",
            title = "Nachrichten 19:20 | 31.08.",
            showName = "Servus Nachrichten",
            duration = 12 * 60 * 1000L,
            playable = true,
        )

        assertFalse(ServusCatalogPolicy.belongsToShow(card, "WEATHER", "Servus Wetter"))
        assertNull(
            ServusCatalogPolicy.toShowEpisode(
                card, "WEATHER", "Servus Wetter", "CAT", "News & Magazine", null,
                Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(),
            ),
        )
    }

    @Test
    fun episodeTitleCanProvideStrongShowMembershipWhenShowNameIsMissing() {
        val card = ServusCardDto(
            id = "WEATHER-31",
            type = "video",
            contentType = "episode",
            title = "31.08. | Servus Wetter",
            duration = 4 * 60 * 1000L,
            playable = true,
        )

        assertTrue(ServusCatalogPolicy.belongsToShow(card, "WEATHER", "Servus Wetter"))
        assertNotNull(
            ServusCatalogPolicy.toShowEpisode(
                card, "WEATHER", "Servus Wetter", "CAT", "News & Magazine", null,
                Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(),
            ),
        )
    }

    @Test
    fun exactShowNameProvidesMembershipForGenericEpisodeTitle() {
        val card = ServusCardDto(
            id = "GENERIC",
            type = "video",
            contentType = "episode",
            title = "Folge vom 31.08.",
            showName = "Servus am Abend",
            duration = 24 * 60 * 1000L,
            playable = true,
        )

        assertTrue(ServusCatalogPolicy.belongsToShow(card, "EVENING", "Servus am Abend"))
    }

    @Test
    fun fullEpisodesWinOverClipsForShowChannel() {
        val now = Instant.parse("2026-09-01T06:00:00Z").toEpochMilli()
        val full = ServusCatalogPolicy.toShowEpisode(
            ServusCardDto(
                id = "EP",
                type = "video",
                contentType = "episode",
                title = "Ganze Folge",
                showName = "Test",
                duration = 30 * 60 * 1000L,
                playable = true,
                sunriseTimestamp = "2026-09-01T05:00:00Z",
            ),
            "SHOW", "Test", "CAT", "Wissen", null, now,
        )!!
        val clip = ServusCatalogPolicy.toShowEpisode(
            ServusCardDto(
                id = "CLIP",
                type = "video",
                contentType = "clip",
                title = "Clip",
                showName = "Test",
                duration = 3 * 60 * 1000L,
                playable = true,
                sunriseTimestamp = "2026-09-01T05:30:00Z",
            ),
            "SHOW", "Test", "CAT", "Wissen", null, now,
        )!!

        val result = ServusCatalogPolicy.selectChannelEpisodes(listOf(clip, full))
        assertEquals(listOf("EP"), result.map { it.id })
    }

    @Test
    fun clipsRemainFallbackWhenShowHasNoFullEpisodes() {
        val clip = ServusNewsEpisode(
            id = "CLIP",
            title = "Clip",
            showName = "Test",
            description = null,
            durationMillis = 120_000L,
            publishedAtMillis = 1000L,
            artworkUri = null,
            showId = "SHOW",
            contentType = "clip",
        )
        assertEquals(listOf("CLIP"), ServusCatalogPolicy.selectChannelEpisodes(listOf(clip)).map { it.id })
    }

    @Test
    fun nextCollectionOffsetIsReadFromApiMetaLink() {
        assertEquals(30, ServusCatalogPolicy.nextOffset("/collections/v5.3/stv/de/at/x?offset=30"))
        assertEquals(null, ServusCatalogPolicy.nextOffset(null))
    }

    @Test
    fun liveDestinationUrlMatchesCurrentKodiAddonPattern() {
        assertEquals(
            "https://dms.redbull.tv/v5/destination/stv/PN123/personal_computer/http/de/at/playlist.m3u8",
            ServusPlaybackResolver.buildLiveDestinationUrl("PN123", "at"),
        )
        assertTrue(ServusPlaybackResolver.isMainServusLive("ServusTV Live"))
        assertTrue(ServusPlaybackResolver.isMainServusLive("ServusTV: Der Livestream"))
        assertFalse(ServusPlaybackResolver.isMainServusLive("Wissen On"))
    }

    @Test
    fun guideProgramUsesStartAndEndTime() {
        val program = ServusCatalogPolicy.liveProgram(
            ServusCardDto(
                id = "P1",
                title = "Dokumentation",
                subheading = "Folge 1",
                startTime = "2026-09-01T05:00:00Z",
                endTime = "2026-09-01T06:00:00Z",
            ),
        )
        assertNotNull(program)
        assertEquals(Instant.parse("2026-09-01T05:00:00Z").toEpochMilli(), program!!.startAtMillis)
    }
}
