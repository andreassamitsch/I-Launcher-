package com.andreassamitsch.ilauncher.data.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogPolicyTest {
    @Test
    fun `prefers TV launcher activity and removes own package`() {
        val candidates = listOf(
            AppCandidate("com.example.video", "PhoneActivity", "Video", isTvApp = false),
            AppCandidate("com.example.video", "TvActivity", "Video", isTvApp = true),
            AppCandidate("com.andreassamitsch.ilauncher", "MainActivity", "I Launcher", isTvApp = true),
        )

        val result = AppCatalogPolicy.select(
            candidates = candidates,
            ownPackageName = "com.andreassamitsch.ilauncher",
        )

        assertEquals(1, result.size)
        assertEquals("TvActivity", result.single().activityName)
    }

    @Test
    fun `sorts apps alphabetically independent of input order`() {
        val candidates = listOf(
            AppCandidate("com.z", "Z", "Zeta", isTvApp = true),
            AppCandidate("com.a", "A", "Alpha", isTvApp = true),
            AppCandidate("com.b", "B", "beta", isTvApp = true),
        )

        val result = AppCatalogPolicy.select(candidates, ownPackageName = "self")

        assertEquals(listOf("Alpha", "beta", "Zeta"), result.map { it.label })
    }
}
