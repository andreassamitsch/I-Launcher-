package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoynPublicArtworkPolicyTest {
    @Test
    fun `phone primary wins over art logo and hero landscape`() {
        val html = """
            <img src="https://img.joyn.de/ingest/t_001/i_6xh0psif3qa9.png/profile:nextgen-web-artlogo-360x148">
            <img src="https://img.joyn.de/ingest/t_001/i_1n9l7qtvzz1e.jpg/profile:nextgen-web-herolandscape-1920x">
            <img src="https://img.joyn.de/ingest/t_001/i_gcor24clf5ti_279c156f.jpg/profile:nextgen-webphone-primary-768x432.webp">
        """.trimIndent()

        assertEquals(
            "https://img.joyn.de/ingest/t_001/i_gcor24clf5ti_279c156f.jpg/profile:nextgen-webphone-primary-768x432.webp",
            selectJoynPublicPageArtwork(html),
        )
    }

    @Test
    fun `hero landscape is used when page has no phone primary`() {
        val html = """
            {"logo":"https:\/\/img.joyn.de\/ingest\/t_001\/logo.png\/profile:nextgen-web-artlogo-360x148",
             "hero":"https:\/\/img.joyn.de\/ingest\/t_001\/hero.jpg\/profile:nextgen-web-herolandscape-1920x"}
        """.trimIndent()

        assertEquals(
            "https://img.joyn.de/ingest/t_001/hero.jpg/profile:nextgen-web-herolandscape-1920x",
            selectJoynPublicPageArtwork(html),
        )
    }

    @Test
    fun `logo only page returns no content artwork`() {
        assertNull(
            selectJoynPublicPageArtwork(
                "https://img.joyn.de/ingest/t_001/logo.png/profile:nextgen-web-artlogo-360x148",
            ),
        )
    }
}
