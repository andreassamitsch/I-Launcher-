package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynNextEpisodePolicyTest {
    @Test
    fun nextInSeasonSelectsLowestHigherEpisode() {
        val episodes = listOf(
            episode(8),
            episode(10),
            episode(9),
        )

        assertEquals(9, JoynNextEpisodePolicy.nextInSeason(8, episodes)?.episodeNumber)
    }

    @Test
    fun nextInSeasonReturnsNullWhenNothingNewExists() {
        val episodes = listOf(episode(7), episode(8))

        assertNull(JoynNextEpisodePolicy.nextInSeason(8, episodes))
    }

    @Test
    fun firstEpisodeSupportsSeasonTransitionBackToEpisodeOne() {
        val episodes = listOf(episode(3), episode(1), episode(2))

        assertEquals(1, JoynNextEpisodePolicy.firstEpisode(episodes)?.episodeNumber)
    }

    private fun episode(number: Int) = JoynMediaItem(
        id = "episode-$number",
        title = "Folge $number",
        type = JoynMediaType.EPISODE,
        videoId = "video-$number",
        seasonId = "season",
        seriesTitle = "Serie",
        seasonNumber = 1,
        episodeNumber = number,
    )
}
