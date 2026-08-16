package com.andreassamitsch.ilauncher.data.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamExplicitSearchTest {
    @Test
    fun `explicit search keeps CloudStream search route even when direct play exists`() {
        assertEquals(
            "cloudstreamsearch://Fallout",
            buildCloudStreamSearchUri("  Fallout  "),
        )
    }

    @Test
    fun `CloudStream package detection accepts supported variants only`() {
        assertTrue(isCloudStreamPackageName("com.lagradost.cloudstream3"))
        assertTrue(isCloudStreamPackageName("com.lagradost.cloudstream3.prerelease.debug"))
        assertFalse(isCloudStreamPackageName("com.netflix.ninja"))
        assertFalse(isCloudStreamPackageName(null))
    }
}
