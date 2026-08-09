package com.andreassamitsch.ilauncher.ui.livetv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvZappingTest {
    @Test
    fun `moves through receiver order`() {
        assertEquals(3, LiveTvZapping.nextIndex(currentIndex = 2, size = 6, delta = 1))
        assertEquals(1, LiveTvZapping.nextIndex(currentIndex = 2, size = 6, delta = -1))
    }

    @Test
    fun `wraps at bouquet boundaries`() {
        assertEquals(0, LiveTvZapping.nextIndex(currentIndex = 5, size = 6, delta = 1))
        assertEquals(5, LiveTvZapping.nextIndex(currentIndex = 0, size = 6, delta = -1))
    }

    @Test
    fun `handles empty and invalid current index safely`() {
        assertEquals(0, LiveTvZapping.nextIndex(currentIndex = 4, size = 0, delta = 1))
        assertEquals(1, LiveTvZapping.nextIndex(currentIndex = 99, size = 3, delta = -1))
    }
}
