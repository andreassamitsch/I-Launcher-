package com.andreassamitsch.ilauncher.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMetadataUrlTest {
    @Test
    fun `metadata url gets unique cache buster`() {
        val first = buildUpdateMetadataUrl(100L)
        val second = buildUpdateMetadataUrl(101L)

        assertTrue(first.startsWith("https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/downloads/update.json?check="))
        assertEquals("https://raw.githubusercontent.com/andreassamitsch/I-Launcher-/downloads/update.json?check=100", first)
        assertNotEquals(first, second)
    }
}
