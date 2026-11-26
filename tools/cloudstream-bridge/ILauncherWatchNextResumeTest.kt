package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Test

class ILauncherWatchNextResumeTest {
    @Test fun sameTicketSurvivesReloadButDispatchesOnce() {
        val gate = ILauncherWatchNextResume.ResumeGate()
        gate.register("a")
        assertEquals(true, gate.isPending("a"))
        assertEquals(true, gate.isPending("a"))
        assertEquals(true, gate.claim("a"))
        assertEquals(false, gate.claim("a"))
        assertEquals(false, gate.isPending("a"))
    }

    @Test fun supersededRequestCannotClaim() {
        val gate = ILauncherWatchNextResume.ResumeGate()
        gate.register("a")
        gate.register("b")
        assertEquals(false, gate.claim("a"))
        assertEquals(true, gate.claim("b"))
    }

    @Test fun exactPercyJacksonEpisodeIsSelected() {
        val rows = listOf(
            ILauncherWatchNextResume.Coordinate(-505390427, 1, 1),
            ILauncherWatchNextResume.Coordinate(-505390429, 1, 3),
            ILauncherWatchNextResume.Coordinate(-505290429, 2, 3),
        )
        assertEquals(-505390429, ILauncherWatchNextResume.select(rows, -505390429, 1, 3))
        assertEquals(-505390429, ILauncherWatchNextResume.select(rows, 123, 1, 3))
        assertEquals(-505390429, ILauncherWatchNextResume.select(rows, -505290429, 1, 3))
        assertEquals(null, ILauncherWatchNextResume.select(rows, 123, 1, 4))
        assertEquals(null, ILauncherWatchNextResume.select(rows, 123, null, 3))
    }

    @Test fun ambiguousCoordinateDoesNotStartWrongEpisode() {
        val rows = listOf(
            ILauncherWatchNextResume.Coordinate(100, 1, 3),
            ILauncherWatchNextResume.Coordinate(200, 1, 3),
        )
        assertEquals(null, ILauncherWatchNextResume.select(rows, 300, 1, 3))
        assertEquals(200, ILauncherWatchNextResume.select(rows, 200, 1, 3))
    }
}
