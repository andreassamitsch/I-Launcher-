package com.andreassamitsch.joyntv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoynCatalogueUiPolicyTest {
    @Test
    fun `internal categories and genre targets are hidden`() {
        val category = JoynMediaItem(
            id = "category",
            title = "Kategorien",
            path = "block-123",
            type = JoynMediaType.CATEGORY,
        )
        val genre = JoynMediaItem(
            id = "genre",
            title = "Action",
            path = "/serien/genre/action",
            type = JoynMediaType.COLLECTION,
        )
        val movie = JoynMediaItem(
            id = "movie",
            title = "Film",
            path = "/filme/film",
            type = JoynMediaType.MOVIE,
        )

        val visible = JoynCataloguePage(
            title = "Filme",
            lanes = listOf(JoynLane("lane", "Genres", listOf(category, genre, movie))),
        ).forJoynUi()

        assertEquals(listOf("movie"), visible.lanes.single().items.map { it.id })
        assertTrue(category.isUnsupportedJoynBrowseTarget())
        assertTrue(genre.isUnsupportedJoynBrowseTarget())
        assertFalse(movie.isUnsupportedJoynBrowseTarget())
    }

    @Test
    fun `lane is removed when only unsupported targets remain`() {
        val genre = JoynMediaItem(
            id = "genre",
            title = "Doku",
            path = "/filme/genre/doku",
            type = JoynMediaType.COLLECTION,
        )
        val visible = JoynCataloguePage(
            title = "Filme",
            lanes = listOf(JoynLane("genres", "Genres", listOf(genre))),
        ).forJoynUi()

        assertTrue(visible.lanes.isEmpty())
    }

    @Test
    fun `redundant live tv zappn catalogue lane is removed`() {
        val teaser = JoynMediaItem(id = "live", title = "Kommissar Rex")
        val content = JoynMediaItem(id = "movie", title = "Film", type = JoynMediaType.MOVIE)
        val visible = JoynCataloguePage(
            title = "Start",
            lanes = listOf(
                JoynLane("live-teasers", "Live-TV zappn", listOf(teaser)),
                JoynLane("content", "Highlights", listOf(content)),
            ),
        ).forJoynUi()

        assertEquals(listOf("content"), visible.lanes.map { it.id })
    }

    @Test
    fun `pure channel shelves move behind content shelves`() {
        val channel = JoynMediaItem(id = "orf", title = "ORF1", type = JoynMediaType.CHANNEL)
        val movie = JoynMediaItem(id = "movie", title = "Film", type = JoynMediaType.MOVIE)
        val visible = JoynCataloguePage(
            title = "Start",
            lanes = listOf(
                JoynLane("mediatheken", "Mediatheken", listOf(channel)),
                JoynLane("content", "Top 10", listOf(movie)),
            ),
        ).forJoynUi()

        assertEquals(listOf("content", "mediatheken"), visible.lanes.map { it.id })
    }

    @Test
    fun `literal null and blank headings are not displayed`() {
        assertNull("null".joynDisplayTitleOrNull())
        assertNull(" NULL ".joynDisplayTitleOrNull())
        assertNull("   ".joynDisplayTitleOrNull())
        assertEquals("Highlights", " Highlights ".joynDisplayTitleOrNull())
    }
}
