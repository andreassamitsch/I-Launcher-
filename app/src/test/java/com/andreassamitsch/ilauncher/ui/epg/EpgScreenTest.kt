package com.andreassamitsch.ilauncher.ui.epg

import com.andreassamitsch.ilauncher.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgScreenTest {
    @Test
    fun `positions guide on current programme`() {
        val programmes = listOf(
            program(start = 1_000L, duration = 1_000L),
            program(start = 2_000L, duration = 1_000L),
            program(start = 3_000L, duration = 1_000L),
        )

        assertEquals(1, initialProgramIndex(programmes, nowUtcMillis = 2_500L))
    }

    @Test
    fun `positions guide on next programme when none is current`() {
        val programmes = listOf(
            program(start = 1_000L, duration = 500L),
            program(start = 2_000L, duration = 500L),
            program(start = 3_000L, duration = 500L),
        )

        assertEquals(1, initialProgramIndex(programmes, nowUtcMillis = 1_700L))
    }

    @Test
    fun `positions guide on last programme after guide window`() {
        val programmes = listOf(
            program(start = 1_000L, duration = 500L),
            program(start = 2_000L, duration = 500L),
        )

        assertEquals(1, initialProgramIndex(programmes, nowUtcMillis = 5_000L))
        assertEquals(-1, initialProgramIndex(emptyList(), nowUtcMillis = 5_000L))
    }

    private fun program(start: Long, duration: Long): LiveTvProgram = LiveTvProgram(
        eventId = null,
        title = "Test",
        startUtcMillis = start,
        durationMillis = duration,
    )
}
