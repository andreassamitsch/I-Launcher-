package com.andreassamitsch.ilauncher.data.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSearchHandoffTest {
    @Test
    fun `normalizes whitespace before external search`() {
        assertEquals(
            "Dune: Part Two",
            normalizeContentSearchQuery("  Dune:   Part\nTwo  "),
        )
    }

    @Test
    fun `keeps unicode title intact`() {
        assertEquals(
            "Die drei ???",
            normalizeContentSearchQuery("Die drei ???"),
        )
    }
}
