package com.andreassamitsch.ilauncher.data.tv

import android.media.tv.TvContract
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
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
                    type = TvContract.Channels.TYPE_TUNER,
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
        assertEquals("example.package", item.media.source.packageName)
        assertEquals(90, item.weight)
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
        browsable: Int? = 1,
        type: String? = TvContract.Channels.TYPE_PREVIEW,
        programs: List<PreviewProgramRawRow>,
    ) = PreviewChannelRawRow(
        id = id,
        sourceOrder = sourceOrder,
        packageName = "example.package",
        displayName = name,
        appLinkIntentUri = null,
        browsable = browsable,
        type = type,
        programs = programs,
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
        shortDescription = null,
        posterArtUri = null,
        thumbnailUri = null,
        logoUri = null,
        intentUri = "intent:#Intent;end",
        durationMillis = null,
        weight = weight,
        browsable = browsable,
        searchable = searchable,
    )
}
