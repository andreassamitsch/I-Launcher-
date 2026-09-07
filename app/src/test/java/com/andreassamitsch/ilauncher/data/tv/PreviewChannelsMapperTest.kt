package com.andreassamitsch.ilauncher.data.tv

import android.media.tv.TvContract
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewChannelsMapperTest {
    @Test
    fun `preserves TvProvider channel and program order`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Neu",
                    programs = listOf(program(101, 0, "A"), program(102, 1, "B")),
                ),
                channel(
                    id = 20,
                    sourceOrder = 1,
                    name = "Danach",
                    programs = listOf(program(201, 0, "C")),
                ),
            ),
        )

        assertEquals(listOf("Neu", "Danach"), mapped.map { it.title })
        assertEquals(listOf(0, 1), mapped.map { it.sourceOrder })
        assertEquals(listOf("A", "B"), mapped.first().programs.map { it.media.title })
        assertEquals(listOf(0, 1), mapped.first().programs.map { it.sourceOrder })
    }

    @Test
    fun `system channel browsable does not hide content from I Launcher`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "System sichtbar",
                    browsable = 1,
                    programs = listOf(program(1, 0, "A")),
                ),
                channel(
                    id = 20,
                    sourceOrder = 1,
                    name = "System ausgeblendet",
                    browsable = 0,
                    programs = listOf(program(2, 0, "B")),
                ),
                channel(
                    id = 30,
                    sourceOrder = 2,
                    name = "Systemstatus fehlt",
                    browsable = null,
                    programs = listOf(program(3, 0, "C")),
                ),
            ),
        )

        assertEquals(
            listOf("System sichtbar", "System ausgeblendet", "Systemstatus fehlt"),
            mapped.map { it.title },
        )
        assertEquals(listOf("A", "B", "C"), mapped.map { it.programs.single().media.title })
    }

    @Test
    fun `filters preview programs Android explicitly hides`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Programme",
                    browsable = 0,
                    programs = listOf(
                        program(1, 0, "Visible"),
                        program(2, 1, "Not browsable", browsable = 0),
                        program(3, 2, "Not searchable", searchable = 0),
                        program(4, 3, "Missing browsable", browsable = null),
                        program(5, 4, "Missing searchable", searchable = null),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Visible"), mapped.single().programs.map { it.media.title })
    }

    @Test
    fun `filters non preview channels`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Kein Preview",
                    type = "TYPE_NON_PREVIEW_TEST",
                    programs = listOf(program(1, 0, "Hidden")),
                ),
            ),
        )

        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `maps episodic preview metadata into provider neutral media`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Serien",
                    programs = listOf(
                        program(
                            id = 1,
                            sourceOrder = 0,
                            title = "Fallout",
                            programType = TvContract.PreviewPrograms.TYPE_TV_EPISODE,
                            season = "2",
                            episode = "4",
                            episodeTitle = "Folge vier",
                            releaseDate = "2026-01-02",
                            weight = 90,
                        ),
                    ),
                ),
            ),
        )

        val item = mapped.single().programs.single()
        assertEquals(MediaType.Episode, item.media.type)
        assertEquals("S2 E4 · Folge vier", item.media.subtitle)
        assertEquals(2026, item.media.releaseYear)
        assertEquals("2026-01-02", item.media.releaseDate)
        assertEquals("example.package", item.media.source.packageName)
        assertEquals(90, item.weight)
    }

    @Test
    fun `Servus current channel uses show art for card episode art for hero and title treatment`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 90,
                    sourceOrder = 0,
                    name = "ServusTV Aktuelles",
                    packageName = SERVUS_PROVIDER_PACKAGE,
                    internalProviderId = SERVUS_CURRENT_CHANNEL_ID,
                    programs = listOf(
                        program(
                            id = 9001,
                            sourceOrder = 0,
                            title = "Leiche in abgeschlepptem Auto entdeckt",
                            programType = TvContract.PreviewPrograms.TYPE_TV_EPISODE,
                            posterArtUri = "https://example.test/show-landscape.webp",
                            thumbnailUri = "https://example.test/episode-landscape.webp",
                            logoUri = "https://example.test/title-treatment.webp",
                        ),
                    ),
                ),
            ),
        )

        val media = mapped.single().programs.single().media
        assertEquals("https://example.test/show-landscape.webp", media.sourceArtworkUri)
        assertEquals("https://example.test/show-landscape.webp", media.preferredArtworkUri)
        assertEquals("https://example.test/episode-landscape.webp", media.episodeStillUri)
        assertEquals("https://example.test/episode-landscape.webp", media.backdropUri)
        assertEquals("https://example.test/episode-landscape.webp", media.heroBackdropUri)
        assertEquals("https://example.test/title-treatment.webp", media.logoUri)
    }

    @Test
    fun `Servus current 90-second entry still uses bundled title logo`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 90,
                    sourceOrder = 0,
                    name = "ServusTV Aktuelles",
                    packageName = SERVUS_PROVIDER_PACKAGE,
                    internalProviderId = SERVUS_CURRENT_CHANNEL_ID,
                    programs = listOf(
                        program(
                            id = 9002,
                            sourceOrder = 0,
                            title = "Aktuelle Meldungen",
                            programType = TvContract.PreviewPrograms.TYPE_TV_EPISODE,
                            shortDescription = SERVUS_90_SHOW_NAME,
                            posterArtUri = "https://example.test/show-landscape.webp",
                            thumbnailUri = "https://example.test/episode-landscape.webp",
                            logoUri = null,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.servus_news_90_logo}",
            mapped.single().programs.single().media.logoUri,
        )
    }

    @Test
    fun `dedicated Servus channel keeps episode art and logo behavior`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 91,
                    sourceOrder = 0,
                    name = SERVUS_90_SHOW_NAME,
                    packageName = SERVUS_PROVIDER_PACKAGE,
                    internalProviderId = "servus-show:AAYGF2URW6ALQYE42IJK",
                    programs = listOf(
                        program(
                            id = 9002,
                            sourceOrder = 0,
                            title = "Nachrichtenfolge",
                            shortDescription = SERVUS_90_SHOW_NAME,
                            posterArtUri = "https://example.test/episode-poster.webp",
                            thumbnailUri = "https://example.test/episode-thumbnail.webp",
                            logoUri = null,
                        ),
                    ),
                ),
            ),
        )

        val media = mapped.single().programs.single().media
        assertEquals("https://example.test/episode-thumbnail.webp", media.sourceArtworkUri)
        assertNull(media.heroBackdropUri)
        assertEquals(
            "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.servus_news_90_logo}",
            media.logoUri,
        )
    }

    @Test
    fun `uses bundled Servus 90-second logo when TvProvider logo uri is missing`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 90,
                    sourceOrder = 0,
                    name = "ServusTV Aktuelles",
                    packageName = SERVUS_PROVIDER_PACKAGE,
                    programs = listOf(
                        program(
                            id = 9001,
                            sourceOrder = 0,
                            title = "Attersee-Obduktionsergebnis ist da",
                            shortDescription = SERVUS_90_SHOW_NAME,
                            logoUri = null,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.servus_news_90_logo}",
            mapped.single().programs.single().media.logoUri,
        )
    }

    @Test
    fun `does not infer Servus branding for another provider`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 90,
                    sourceOrder = 0,
                    name = "Andere App",
                    packageName = "example.package",
                    programs = listOf(
                        program(
                            id = 9001,
                            sourceOrder = 0,
                            title = "Attersee-Obduktionsergebnis ist da",
                            shortDescription = SERVUS_90_SHOW_NAME,
                            logoUri = null,
                        ),
                    ),
                ),
            ),
        )

        assertNull(mapped.single().programs.single().media.logoUri)
    }

    @Test
    fun `uses Android preview program weight descending query`() {
        assertEquals("weight DESC", PreviewChannelsRepository.PROGRAM_SORT_ORDER)
    }

    @Test
    fun `keeps preview channel with empty program list for diagnostics and settings`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Leer",
                    browsable = 0,
                    programs = emptyList(),
                ),
            ),
        )

        assertEquals(1, mapped.size)
        assertTrue(mapped.single().programs.isEmpty())
    }

    private fun channel(
        id: Long,
        sourceOrder: Int,
        name: String,
        packageName: String = "example.package",
        browsable: Int? = 1,
        type: String? = TvContract.Channels.TYPE_PREVIEW,
        internalProviderId: String? = null,
        programs: List<PreviewProgramRawRow>,
    ) = PreviewChannelRawRow(
        id = id,
        sourceOrder = sourceOrder,
        packageName = packageName,
        displayName = name,
        appLinkIntentUri = null,
        browsable = browsable,
        type = type,
        programs = programs,
        internalProviderId = internalProviderId,
    )

    private fun program(
        id: Long,
        sourceOrder: Int,
        title: String,
        programType: Int? = TvContract.PreviewPrograms.TYPE_MOVIE,
        season: String? = null,
        episode: String? = null,
        episodeTitle: String? = null,
        releaseDate: String? = null,
        shortDescription: String? = null,
        posterArtUri: String? = null,
        thumbnailUri: String? = null,
        logoUri: String? = null,
        weight: Int? = null,
        browsable: Int? = 1,
        searchable: Int? = 1,
    ) = PreviewProgramRawRow(
        id = id,
        sourceOrder = sourceOrder,
        packageName = null,
        programType = programType,
        title = title,
        releaseDate = releaseDate,
        seasonDisplayNumber = season,
        episodeDisplayNumber = episode,
        episodeTitle = episodeTitle,
        shortDescription = shortDescription,
        posterArtUri = posterArtUri,
        thumbnailUri = thumbnailUri,
        logoUri = logoUri,
        intentUri = "intent:#Intent;end",
        durationMillis = null,
        weight = weight,
        browsable = browsable,
        searchable = searchable,
    )

    private companion object {
        const val SERVUS_PROVIDER_PACKAGE = "com.andreassamitsch.servusprovider"
        const val SERVUS_CURRENT_CHANNEL_ID = "servus-news-19-20"
        const val SERVUS_90_SHOW_NAME = "Servus Nachrichten in 90 Sekunden"
    }
}
