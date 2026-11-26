package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Test

class JoynSeriesDisplayOrderTest {
    @Test
    fun latestSeasonIsFirstAndSeasonNumbersSortNumerically() {
        val seasons = listOf(JoynSeason("s2", 2), JoynSeason("s10", 10), JoynSeason("s1", 1))
        assertEquals(listOf(10, 2, 1), JoynSeriesDisplayOrder.seasons(seasons).map { it.number })
    }

    @Test
    fun newestEpisodesFirstWithUnnumberedEpisodesLastAndStableTies() {
        val episodes = listOf(episode("a", null), episode("b", 2), episode("c", 10),
            episode("d", 10), episode("e", null), episode("f", 1))
        val ordered = JoynSeriesDisplayOrder.episodes(episodes)
        assertEquals(listOf("c", "d", "b", "f", "a", "e"), ordered.map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), episodes.map { it.id })
    }

    @Test
    fun emptyListsStayEmpty() {
        assertEquals(emptyList<JoynSeason>(), JoynSeriesDisplayOrder.seasons(emptyList()))
        assertEquals(emptyList<JoynMediaItem>(), JoynSeriesDisplayOrder.episodes(emptyList()))
    }

    @Test
    fun descendingBrowseOrderDoesNotChangeChronologicalWatchNext() {
        val ordered = JoynSeriesDisplayOrder.episodes(listOf(
            episode("e12", 12), episode("e10", 10), episode("e11", 11)
        ))
        assertEquals("e12", ordered.first().id)
        assertEquals("e11", JoynNextEpisodePolicy.nextInSeason(10, ordered)?.id)
        assertEquals("e10", JoynNextEpisodePolicy.firstEpisode(ordered)?.id)
    }

    private fun episode(id: String, number: Int?) = JoynMediaItem(
        id = id, title = id, type = JoynMediaType.EPISODE, episodeNumber = number,
    )
}
