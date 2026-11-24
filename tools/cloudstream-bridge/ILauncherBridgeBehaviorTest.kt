package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ILauncherBridgeBehaviorTest {
    @Test
    fun watchNextIntentCarriesExactEpisodeIdentity() {
        val raw = ILauncherWatchNextIntent.build(
            parentId = 101,
            episodeId = 707,
            season = 1,
            episode = 7,
            fallbackUrl = "fallback",
        )

        val target = ILauncherWatchNextIntent.parse(raw)
        requireNotNull(target)
        assertEquals(101, target.seriesKey)
        assertEquals(707, target.episodeId)
        assertEquals(1, target.season)
        assertEquals(7, target.episode)
    }

    @Test
    fun staleWatchNextCoordinatesNeverRewindWithinProvider() {
        assertEquals(true, ILauncherWatchNextIntent.isOlderEpisode(1, 3, 1, 7))
        assertEquals(true, ILauncherWatchNextIntent.isOlderEpisode(1, 10, 2, 1))
        assertEquals(false, ILauncherWatchNextIntent.isOlderEpisode(2, 1, 1, 10))
        assertEquals(false, ILauncherWatchNextIntent.isOlderEpisode(2, 3, 2, 3))
        assertEquals(false, ILauncherWatchNextIntent.isOlderEpisode(null, 3, 2, 3))
    }

    @Test
    fun legacyWatchNextIntentStillParses() {
        val target = ILauncherWatchNextIntent.parse("cloudstreamcontinuewatching://404")
        requireNotNull(target)
        assertEquals(404, target.seriesKey)
        assertNull(target.episodeId)
        assertNull(target.season)
        assertNull(target.episode)
    }

    @Test
    fun movieAndSeriesProviderOrdersAreSeparate() {
        val movieKey = ILauncherBridgePreferences.providerOrderKey(ILauncherDirectPlay.MediaKind.Movie)
        val seriesKey = ILauncherBridgePreferences.providerOrderKey(ILauncherDirectPlay.MediaKind.Series)
        val episodeKey = ILauncherBridgePreferences.providerOrderKey(ILauncherDirectPlay.MediaKind.Episode)

        assertNotEquals(movieKey, seriesKey)
        assertEquals(seriesKey, episodeKey)
    }
}
