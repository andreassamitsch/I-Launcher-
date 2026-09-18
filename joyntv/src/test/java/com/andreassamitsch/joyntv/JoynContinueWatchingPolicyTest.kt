package com.andreassamitsch.joyntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynContinueWatchingPolicyTest {
    private val movie = JoynMediaItem(
        id = "movie",
        title = "Film",
        type = JoynMediaType.MOVIE,
        videoId = "movie-video",
    )
    private val episode = JoynMediaItem(
        id = "episode",
        title = "Folge",
        type = JoynMediaType.EPISODE,
        videoId = "episode-video",
    )
    private val liveLike = JoynMediaItem(
        id = "channel",
        title = "Sender",
        type = JoynMediaType.CHANNEL,
    )

    @Test
    fun liveAndChannelContentNeverEntersContinueWatching() {
        assertFalse(JoynContinueWatchingPolicy.shouldContinue(liveLike, 10 * 60_000L, 60 * 60_000L))
    }

    @Test
    fun episodeNeedsTwoMinutesBeforePublishing() {
        assertFalse(JoynContinueWatchingPolicy.shouldContinue(episode, 119_000L, 45 * 60_000L))
        assertTrue(JoynContinueWatchingPolicy.shouldContinue(episode, 120_000L, 45 * 60_000L))
    }

    @Test
    fun movieUsesEarlierOfThreePercentAndTwoMinutes() {
        val duration = 60 * 60_000L
        assertFalse(JoynContinueWatchingPolicy.shouldContinue(movie, 107_000L, duration))
        assertTrue(JoynContinueWatchingPolicy.shouldContinue(movie, 108_000L, duration))
    }

    @Test
    fun nearlyFinishedVodIsRemoved() {
        val duration = 45 * 60_000L
        assertTrue(JoynContinueWatchingPolicy.isFinished(duration - 120_000L, duration))
        assertFalse(JoynContinueWatchingPolicy.shouldContinue(episode, duration - 120_000L, duration))
    }
}
