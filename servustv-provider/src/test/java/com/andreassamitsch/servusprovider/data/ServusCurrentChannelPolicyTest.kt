package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusCurrentChannelPolicyTest {
    @Test
    fun legacyCurrentShowsAreSelectedByDefault() {
        val categories = listOf(
            category(
                show("news", "Servus Nachrichten"),
                show("news90", "Servus Nachrichten in 90 Sekunden"),
                show("weg", "Der Wegscheider"),
                show("sport", "Sport und Talk aus dem Hangar-7"),
            ),
        )

        assertEquals(
            setOf("news", "news90", "weg"),
            ServusCurrentChannelPolicy.defaultSelectedShowIds(categories),
        )
    }

    @Test
    fun ninetySecondLegacyEpisodeRequiresDedicatedShowWhenCatalogueProvidesOne() {
        val news = show("news", "Servus Nachrichten")
        val news90 = show("news90", "Servus Nachrichten in 90 Sekunden")
        val episode = episode(
            id = "90",
            title = "Aktuelle Meldungen",
            showName = "Servus Nachrichten in 90 Sekunden",
        )

        assertFalse(
            ServusCurrentChannelPolicy.matchesSelectedShow(
                episode = episode,
                selectedShows = listOf(news),
                allShows = listOf(news, news90),
            ),
        )
        assertTrue(
            ServusCurrentChannelPolicy.matchesSelectedShow(
                episode = episode,
                selectedShows = listOf(news90),
                allShows = listOf(news, news90),
            ),
        )
    }

    @Test
    fun episodeWithExplicitShowIdFollowsUserSelection() {
        val selected = show("selected", "Eine Sendung")
        val episode = episode(
            id = "episode",
            title = "Folge 1",
            showName = "Eine Sendung",
        ).copy(showId = "selected")

        assertTrue(
            ServusCurrentChannelPolicy.matchesSelectedShow(
                episode = episode,
                selectedShows = listOf(selected),
                allShows = listOf(selected),
            ),
        )
    }

    private fun category(vararg shows: ServusShow) = ServusCategory(
        id = "category",
        title = "Kategorie",
        order = 0,
        shows = shows.toList(),
    )

    private fun show(id: String, title: String) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "category",
        categoryTitle = "Kategorie",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = null,
        episodes = emptyList(),
    )

    private fun episode(id: String, title: String, showName: String) = ServusNewsEpisode(
        id = id,
        title = title,
        showName = showName,
        description = null,
        durationMillis = 90_000L,
        publishedAtMillis = null,
        artworkUri = null,
    )
}
