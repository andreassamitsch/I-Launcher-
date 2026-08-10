package com.andreassamitsch.ilauncher.data.kodi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KodiSearchLauncherTest {
    @Test
    fun `exact normalized title is preferred`() {
        val exact = KodiSuggestion(
            title = "Two and a Half Men",
            action = null,
            data = "videodb://tvshows/titles/1?showinfo=true",
        )
        val other = KodiSuggestion(
            title = "Two Weeks to Live",
            action = null,
            data = "videodb://tvshows/titles/2?showinfo=true",
        )

        assertEquals(exact, selectKodiSuggestion("  Two & A Half Men  ", listOf(other, exact)))
    }

    @Test
    fun `strong prefix title can be selected`() {
        val csi = KodiSuggestion(
            title = "CSI Crime Scene Investigation",
            action = null,
            data = "videodb://tvshows/titles/3?showinfo=true",
        )

        assertEquals(csi, selectKodiSuggestion("CSI", listOf(csi)))
    }

    @Test
    fun `unrelated suggestion is rejected`() {
        val unrelated = KodiSuggestion(
            title = "NCIS",
            action = null,
            data = "videodb://tvshows/titles/4?showinfo=true",
        )

        assertNull(selectKodiSuggestion("CSI", listOf(unrelated)))
    }
}
