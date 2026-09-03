package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServusCurrentShowMetadataPolicyTest {
    @Test
    fun enrichesSupportedCurrentEpisodesWithMatchingShowLogo() {
        val categories = listOf(
            ServusCategory(
                id = "news",
                title = "Nachrichten",
                order = 0,
                shows = listOf(
                    show("full", "Servus Nachrichten 19:20", "https://cdn/full-logo.webp"),
                    show("short", "Servus Nachrichten in 90 Sekunden", "https://cdn/short-logo.webp"),
                    show("weg", "Der Wegscheider", "https://cdn/weg-logo.webp"),
                ),
            ),
        )
        val episodes = listOf(
            episode("a", "Nachrichten 19:20 | 03.09.", "Servus Nachrichten", 15 * 60_000L),
            episode("b", "Servus Nachrichten in 90 Sekunden", "Servus Nachrichten in 90 Sekunden", 90_000L),
            episode("c", "Der Wegscheider", "Der Wegscheider", 8 * 60_000L),
        )

        val result = ServusCurrentShowMetadataPolicy.enrich(episodes, categories)

        assertEquals("full", result[0].showId)
        assertEquals("https://cdn/full-logo.webp", result[0].logoUri)
        assertEquals("short", result[1].showId)
        assertEquals("https://cdn/short-logo.webp", result[1].logoUri)
        assertEquals("weg", result[2].showId)
        assertEquals("https://cdn/weg-logo.webp", result[2].logoUri)
    }

    @Test
    fun keepsEpisodeUnchangedWhenNoMatchingShowExists() {
        val source = episode("a", "Nachrichten 19:20 | 03.09.", "Servus Nachrichten", 15 * 60_000L)
        val result = ServusCurrentShowMetadataPolicy.enrich(listOf(source), emptyList()).single()
        assertNull(result.showId)
        assertNull(result.logoUri)
    }

    private fun show(id: String, title: String, logo: String) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "news",
        categoryTitle = "Nachrichten",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = logo,
        episodes = emptyList(),
    )

    private fun episode(id: String, title: String, showName: String, duration: Long) = ServusNewsEpisode(
        id = id,
        title = title,
        showName = showName,
        description = null,
        durationMillis = duration,
        publishedAtMillis = null,
        artworkUri = null,
    )
}
