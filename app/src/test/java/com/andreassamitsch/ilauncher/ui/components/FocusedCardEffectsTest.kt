package com.andreassamitsch.ilauncher.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedCardEffectsTest {
    @Test
    fun `blue artwork produces a blue-dominant glow`() {
        val pixels = intArrayOf(
            argb(255, 18, 82, 190),
            argb(255, 32, 126, 225),
            argb(255, 50, 158, 238),
            argb(255, 9, 42, 110),
        )

        val color = requireNotNull(extractArtworkGlowColor(pixels))

        assertTrue(color.blue > color.green)
        assertTrue(color.green > color.red)
    }

    @Test
    fun `warm artwork produces a warm glow distinct from blue artwork`() {
        val warm = requireNotNull(
            extractArtworkGlowColor(
                intArrayOf(
                    argb(255, 225, 123, 24),
                    argb(255, 242, 173, 42),
                    argb(255, 191, 57, 20),
                    argb(255, 124, 38, 14),
                ),
            ),
        )
        val blue = requireNotNull(
            extractArtworkGlowColor(
                intArrayOf(
                    argb(255, 21, 86, 198),
                    argb(255, 42, 145, 226),
                    argb(255, 16, 54, 135),
                ),
            ),
        )

        assertTrue(warm.red > warm.blue)
        assertTrue(blue.blue > blue.red)
        assertTrue(kotlin.math.abs(warm.red - blue.red) > 0.15f)
    }

    @Test
    fun `transparent black and near-white pixels do not dominate`() {
        val color = requireNotNull(
            extractArtworkGlowColor(
                intArrayOf(
                    argb(0, 255, 0, 0),
                    argb(255, 2, 2, 2),
                    argb(255, 250, 250, 250),
                    argb(255, 166, 34, 94),
                    argb(255, 190, 46, 106),
                ),
            ),
        )

        assertTrue(color.red > color.green)
        assertTrue(color.red > color.blue)
    }

    @Test
    fun `empty usable sample returns null`() {
        val color = extractArtworkGlowColor(
            intArrayOf(
                argb(0, 255, 0, 0),
                argb(255, 0, 0, 0),
                argb(255, 255, 255, 255),
            ),
        )

        assertTrue(color == null)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
