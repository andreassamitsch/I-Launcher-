package com.andreassamitsch.ilauncher.data.openwebif

import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWebifStreamResolverTest {
    @Test
    fun `parses direct transport stream URL`() {
        val stream = OpenWebifStreamPlaylist.parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-reconnect=true
            http://gigablue.local:8001/1:0:19:283D:3FB:1:C00000:0:0:0:
            """.trimIndent(),
        )

        requireNotNull(stream)
        assertEquals(
            "http://gigablue.local:8001/1:0:19:283D:3FB:1:C00000:0:0:0:",
            stream.url,
        )
        assertTrue(stream.requestHeaders.isEmpty())
        assertFalse(stream.isHls)
    }

    @Test
    fun `moves embedded OpenWebif streaming credentials into request header`() {
        val stream = OpenWebifStreamPlaylist.parse(
            "http://user:secret@gigablue.local:8001/1:0:1:ABC:DEF:1:C00000:0:0:0:",
        )

        requireNotNull(stream)
        assertEquals(
            "http://gigablue.local:8001/1:0:1:ABC:DEF:1:C00000:0:0:0:",
            stream.url,
        )
        assertEquals(Credentials.basic("user", "secret"), stream.requestHeaders["Authorization"])
    }

    @Test
    fun `recognizes HLS playback URL`() {
        val stream = OpenWebifStreamPlaylist.parse(
            "https://example.test/live/channel/playlist.m3u8?token=temporary",
        )

        requireNotNull(stream)
        assertTrue(stream.isHls)
        assertEquals(
            "https://example.test/live/channel/playlist.m3u8?token=temporary",
            stream.url,
        )
    }

    @Test
    fun `rejects non HTTP playlist entries`() {
        assertNull(OpenWebifStreamPlaylist.parse("file:///tmp/channel.ts"))
        assertNull(OpenWebifStreamPlaylist.parse("#EXTM3U\nplugin://example/live"))
    }
}
