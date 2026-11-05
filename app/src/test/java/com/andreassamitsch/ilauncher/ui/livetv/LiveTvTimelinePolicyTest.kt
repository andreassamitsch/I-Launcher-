package com.andreassamitsch.ilauncher.ui.livetv

import com.andreassamitsch.ilauncher.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvTimelinePolicyTest {
    private val start = 1_000_000L
    private val program = LiveTvProgram(
        eventId = null,
        title = "Testsendung",
        startUtcMillis = start,
        durationMillis = 30 * 60_000L,
    )

    @Test fun halfTimeShowsRemainingAndProgress() {
        val timeline = LiveTvTimelinePolicy.snapshot(program, start + 15 * 60_000L)!!
        assertEquals(0.5f, timeline.progress, 0.001f)
        assertEquals("Noch 15 Min.", timeline.timeLabel)
        assertEquals("15m", timeline.compactLabel)
        assertFalse(timeline.endingSoon)
    }

    @Test fun lastFiveMinutesHighlighted() {
        val timeline = LiveTvTimelinePolicy.snapshot(program, start + 26 * 60_000L)!!
        assertEquals("Noch 4 Min.", timeline.timeLabel)
        assertEquals("4m", timeline.compactLabel)
        assertTrue(timeline.endingSoon)
    }

    @Test fun oneSecondLeftRoundsUpToOneMinute() {
        val timeline = LiveTvTimelinePolicy.snapshot(program, program.endUtcMillis - 1L)!!
        assertEquals("Noch 1 Min.", timeline.timeLabel)
        assertEquals("1m", timeline.compactLabel)
        assertTrue(timeline.endingSoon)
    }

    @Test fun programAtEndMarkedEnded() {
        val timeline = LiveTvTimelinePolicy.snapshot(program, program.endUtcMillis)!!
        assertEquals(1f, timeline.progress, 0.001f)
        assertEquals("Sendung beendet", timeline.timeLabel)
        assertEquals("Ende", timeline.compactLabel)
        assertFalse(timeline.endingSoon)
    }

    @Test fun futureProgramMarkedNotStarted() {
        val timeline = LiveTvTimelinePolicy.snapshot(program, start - 2 * 60_000L)!!
        assertEquals(0f, timeline.progress, 0.001f)
        assertEquals("Beginnt in 2 Min.", timeline.timeLabel)
        assertEquals("in 2m", timeline.compactLabel)
        assertFalse(timeline.endingSoon)
    }

    @Test fun invalidOrAbsentEpgNeverDisplaysInventedProgress() {
        assertNull(LiveTvTimelinePolicy.snapshot(null, start))
        assertNull(LiveTvTimelinePolicy.snapshot(program.copy(durationMillis = 0), start))
        assertNull(LiveTvTimelinePolicy.snapshot(program.copy(durationMillis = -60_000), start))
    }
}
