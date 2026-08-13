package com.andreassamitsch.ilauncher.ui.home

import com.andreassamitsch.ilauncher.data.home.HeroTextScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroTextScrollTest {
    @Test
    fun `same number of lines takes same time at different rendered line heights`() {
        val compact = heroTextScrollDurationMillis(
            distancePx = 72,
            lineHeightPx = 18f,
            speed = HeroTextScrollSpeed.Normal,
        )
        val large = heroTextScrollDurationMillis(
            distancePx = 96,
            lineHeightPx = 24f,
            speed = HeroTextScrollSpeed.Normal,
        )

        assertEquals(compact, large)
    }

    @Test
    fun `faster setting shortens time independent of field length`() {
        val slow = heroTextScrollDurationMillis(90, 18f, HeroTextScrollSpeed.Slow)
        val fast = heroTextScrollDurationMillis(90, 18f, HeroTextScrollSpeed.Fast)

        checkNotNull(slow)
        checkNotNull(fast)
        assert(fast < slow)
    }

    @Test
    fun `off disables automatic scrolling`() {
        assertNull(heroTextScrollDurationMillis(120, 18f, HeroTextScrollSpeed.Off))
    }
}
