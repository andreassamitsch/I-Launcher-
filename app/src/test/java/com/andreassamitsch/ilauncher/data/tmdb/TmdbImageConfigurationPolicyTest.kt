package com.andreassamitsch.ilauncher.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbImageConfigurationPolicyTest {
    @Test
    fun `chooses 1280 backdrop for TV hero when available`() {
        assertEquals(
            "w1280",
            chooseTmdbImageSize(
                sizes = listOf("w300", "w780", "w1280", "original"),
                preferredWidth = 1280,
            ),
        )
    }

    @Test
    fun `uses original still instead of upscaling w300 on TV hero`() {
        assertEquals(
            "original",
            chooseTmdbImageSize(
                sizes = listOf("w92", "w185", "w300", "original"),
                preferredWidth = 780,
            ),
        )
    }

    @Test
    fun `falls back to largest numeric size when original is unavailable`() {
        assertEquals(
            "w780",
            chooseTmdbImageSize(
                sizes = listOf("w300", "w780"),
                preferredWidth = 1280,
            ),
        )
    }
}
