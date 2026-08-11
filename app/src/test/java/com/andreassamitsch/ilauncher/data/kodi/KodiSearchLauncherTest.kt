package com.andreassamitsch.ilauncher.data.kodi

import org.junit.Assert.assertEquals
import org.junit.Test

class KodiSearchLauncherTest {
    @Test
    fun `TMDB Helper search path passes title and searches movies plus TV`() {
        assertEquals(
            "plugin://plugin.video.themoviedb.helper/?info=search&tmdb_type=both&query=ZeroZeroZero+%282019%29",
            tmdbHelperSearchPath("ZeroZeroZero (2019)"),
        )
    }

    @Test
    fun `TMDB Helper search path encodes spaces and umlauts`() {
        assertEquals(
            "plugin://plugin.video.themoviedb.helper/?info=search&tmdb_type=both&query=Der+Pass+%C3%96sterreich",
            tmdbHelperSearchPath("Der Pass Österreich"),
        )
    }
}
