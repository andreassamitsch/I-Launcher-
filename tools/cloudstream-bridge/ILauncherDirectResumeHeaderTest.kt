package com.lagradost.cloudstream3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ILauncherDirectResumeHeaderTest {
    @Test fun directSeriesUsesNativeParentKeyNotEpisodeKey() {
        val header = ILauncherDirectResumeHeader.build(
            1447063914, 1447063914, "ExampleProvider", "https://example.org/series",
            TvType.TvSeries, "Example series", "https://example.org/poster.jpg", 123L,
        )
        assertNotNull(header)
        requireNotNull(header)
        assertEquals(1447063914, header.id)
        assertEquals("ExampleProvider", header.apiName)
        assertEquals("https://example.org/series", header.url)
        assertEquals("Example series", header.name)
        assertEquals("https://example.org/poster.jpg", header.poster)
        assertEquals(TvType.TvSeries, header.type)
        assertEquals(123L, header.cacheTime)
    }

    @Test fun movieMayHaveOpaqueProviderReferenceAndNoCover() {
        val header = requireNotNull(ILauncherDirectResumeHeader.build(
            42, 42, "ExampleProvider", "74882", TvType.Movie, "Example film", null, 123L,
        ))
        assertEquals(42, header.id)
        assertEquals("74882", header.url)
        assertEquals(TvType.Movie, header.type)
        assertNull(header.poster)
    }

    @Test fun mismatchedParentAndIncompleteHeaderAreRejected() {
        assertNull(ILauncherDirectResumeHeader.build(
            1447263917, 1447063914, "ExampleProvider", "74882", TvType.TvSeries, "Example", null, 123L,
        ))
        assertNull(ILauncherDirectResumeHeader.build(
            42, 42, "ExampleProvider", "", TvType.Movie, "Example", null, 123L,
        ))
        assertNull(ILauncherDirectResumeHeader.build(
            42, 42, "", "74882", TvType.Movie, "Example", null, 123L,
        ))
    }
}
