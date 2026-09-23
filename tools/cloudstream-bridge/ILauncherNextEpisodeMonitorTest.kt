package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ILauncherNextEpisodeMonitorTest {
    @Test fun nextEpisodeFollowsSeasonOrderInsteadOfFutureListingOrder() {
        val episodes = listOf(2 to 4, 3 to 2, 2 to 3, 3 to 1, 2 to 1)
        assertEquals(2 to 3, ILauncherNextEpisodeMonitor.nextCoordinates(2, 2, episodes))
        assertEquals(3 to 1, ILauncherNextEpisodeMonitor.nextCoordinates(2, 4, episodes))
        assertNull(ILauncherNextEpisodeMonitor.nextCoordinates(3, 2, episodes))
    }

    @Test fun futureEpisodeDateDoesNotImplyPlayableEpisode() {
        val now = 1_800_000_000_000L
        assertTrue(ILauncherNextEpisodeMonitor.isFuture(1_800_086_400L, now))
        assertFalse(ILauncherNextEpisodeMonitor.isFuture(1_799_900_000_000L, now))
        assertFalse(ILauncherNextEpisodeMonitor.isFuture(null, now))
        assertEquals(1_800_086_400_000L, ILauncherNextEpisodeMonitor.normalizedAirDate(1_800_086_400L))
    }
}
