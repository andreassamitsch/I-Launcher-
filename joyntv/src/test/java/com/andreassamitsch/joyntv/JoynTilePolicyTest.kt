package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynTilePolicyTest {
    @Test
    fun `channel logo profile only uses centered logo`() {
        val logoAsset = "https://images.example/asset.png?profile=nextgen-web-artlogo-400x160"
        val item = JoynMediaItem(
            id = "orf1",
            title = "ORF1",
            type = JoynMediaType.CHANNEL,
            imageUrl = logoAsset,
            backdropUrl = logoAsset,
            logoUrl = "https://images.example/logo-orf1.png",
        )

        val artwork = item.resolveJoynTileArtwork()

        assertNull(artwork.primary)
        assertTrue(artwork.centerLogo)
        assertFalse(artwork.overlayLogo)
    }

    @Test
    fun `channel keeps real key art even when primary and backdrop are identical`() {
        val keyArt = "https://images.example/villa-der-versuchung-landscape.jpg"
        val item = JoynMediaItem(
            id = "villa",
            title = "Villa der Versuchung",
            type = JoynMediaType.CHANNEL,
            imageUrl = keyArt,
            backdropUrl = keyArt,
            logoUrl = "https://images.example/villa-logo.png",
        )

        val artwork = item.resolveJoynTileArtwork()

        assertEquals(keyArt, artwork.primary)
        assertFalse(artwork.centerLogo)
        assertTrue(artwork.overlayLogo)
    }

    @Test
    fun `channel with distinct landscape artwork keeps background and logo overlay`() {
        val item = JoynMediaItem(
            id = "channel",
            title = "Sender",
            type = JoynMediaType.CHANNEL,
            imageUrl = "https://images.example/primary.jpg",
            backdropUrl = "https://images.example/hero-landscape.jpg",
            logoUrl = "https://images.example/logo.png",
        )

        val artwork = item.resolveJoynTileArtwork()

        assertEquals("https://images.example/hero-landscape.jpg", artwork.primary)
        assertFalse(artwork.centerLogo)
        assertTrue(artwork.overlayLogo)
    }

    @Test
    fun `vod ignores art logo image when real backdrop exists`() {
        val item = JoynMediaItem(
            id = "show",
            title = "Villa der Versuchung",
            type = JoynMediaType.SERIES,
            imageUrl = "https://images.example/nextgen-web-artlogo-400x160.png",
            backdropUrl = "https://images.example/show-landscape.jpg",
            logoUrl = "https://images.example/show-logo.png",
        )

        val artwork = item.resolveJoynTileArtwork()

        assertEquals("https://images.example/show-landscape.jpg", artwork.primary)
        assertFalse(artwork.centerLogo)
        assertFalse(artwork.overlayLogo)
    }

    @Test
    fun `vod with no valid art centers its logo fallback`() {
        val item = JoynMediaItem(
            id = "show",
            title = "Show",
            type = JoynMediaType.SERIES,
            logoUrl = "https://images.example/show-logo.png",
        )

        val artwork = item.resolveJoynTileArtwork()

        assertNull(artwork.primary)
        assertTrue(artwork.centerLogo)
    }

    @Test
    fun `longest title word can drive compact poster typography`() {
        assertEquals(9, "Surviving Amazonas - Die Prüfung".longestJoynTitleWordLength())
        assertEquals(0, "   ".longestJoynTitleWordLength())
    }
}
