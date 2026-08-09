package com.andreassamitsch.ilauncher.data.youtube

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeEmbedPlayerTest {
    @Test
    fun `builds autoplay German embed for valid YouTube video id`() {
        val html = YouTubeEmbedPlayer.html("M7lc1UVf-VE")

        assertTrue(html?.contains("https://www.youtube.com/embed/M7lc1UVf-VE") == true)
        assertTrue(html?.contains("autoplay=1") == true)
        assertTrue(html?.contains("controls=1") == true)
        assertTrue(html?.contains("hl=de") == true)
        assertTrue(html?.contains("cc_lang_pref=de") == true)
    }

    @Test
    fun `rejects malformed YouTube video id`() {
        assertNull(YouTubeEmbedPlayer.html("../not-a-video"))
        assertNull(YouTubeEmbedPlayer.html("too-short"))
    }
}
