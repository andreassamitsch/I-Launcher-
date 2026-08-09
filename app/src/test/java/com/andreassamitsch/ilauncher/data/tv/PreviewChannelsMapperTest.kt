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
    fun `filters channels and programs Android marks hidden`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(
                channel(
                    id = 10,
                    sourceOrder = 0,
                    name = "Sichtbar",
                    programs = listOf(
                        program(1, 0, "Visible"),
                        program(2, 1, "Not browsable", browsable = 0),
                        program(3, 2, "Not searchable", searchable = 0),
                    ),
                ),
                channel(
                    id = 20,
                    sourceOrder = 1,
                    name = "Hidden channel",
                    browsable = 0,
                    programs = listOf(program(4, 0, "Hidden")),
                ),
            ),
        )

        assertEquals(1, mapped.size)
        assertEquals(listOf("Visible"), mapped.single().programs.map { it.media.title })
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
    fun `keeps channel with empty program list for diagnostics and settings`() {
        val mapped = PreviewChannelsMapper.map(
            listOf(channel(id = 10, sourceOrder = 0, name = "Leer", programs = emptyList())),
        )

        assertEquals(1, mapped.size)
        assertTrue(mapped.single().programs.isEmpty())
    }

    private fun channel(
        id: Long,
        sourceOrder: Int,
        name: String,
        browsable: Int? = 1,
        programs: List<PreviewProgramRawRow>,
    ) = PreviewChannelRawRow(
        id = id,
        sourceOrder = sourceOrder,
        packageName = "example.package",
        displayName = name,
        appLinkIntentUri = null,
        browsable = browsable,
        type = TvContract.Channels.TYPE_PREVIEW,
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
