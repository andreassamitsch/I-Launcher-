package com.andreassamitsch.ilauncher.data.kodi

import java.io.StringReader
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

    @Test
    fun `raw Kodi response parses without newline delimiter`() {
        val response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}"

        assertEquals(response, readKodiJsonMessage(StringReader(response)))
    }

    @Test
    fun `raw Kodi stream separates notification and response without delimiter`() {
        val notification =
            "{\"jsonrpc\":\"2.0\",\"method\":\"GUI.OnScreensaverDeactivated\",\"params\":{\"data\":{\"label\":\"{Kodi}\"}}}"
        val response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}"
        val reader = StringReader(notification + response)

        assertEquals(notification, readKodiJsonMessage(reader))
        assertEquals(response, readKodiJsonMessage(reader))
    }
}
