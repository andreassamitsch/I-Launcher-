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
    fun canonicalBrandingOverridesStaleGenericLogoInCachedNinetySecondEpisode() {
        val stale = episode(
            id = "90",
            title = "Frau in Graz getötet",
            showName = "Servus Nachrichten in 90 Sekunden",
        ).copy(
            logoUri = "https://cdn.example/servus-nachrichten.webp",
        )

        val result = ServusCurrentChannelPolicy.applyCanonicalBranding(listOf(stale)).single()

        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, result.logoUri)
    }

    @Test
    fun composeCurrentEpisodesOverridesWrongLogoBeforePublishingOrRendering() {
        val news90 = show(
            ServusBranding.NEWS_90_SECONDS_SHOW_ID,
            "Servus Nachrichten in 90 Sekunden",
        )
        val stale = episode(
            id = "90",
            title = "Frau in Graz getötet",
            showName = "Servus Nachrichten in 90 Sekunden",
        ).copy(
            showId = "generic-news",
            logoUri = "https://cdn.example/servus-nachrichten.webp",
        )

        val result = ServusCurrentChannelPolicy.composeCurrentEpisodes(
            selectedShows = listOf(news90),
            allShows = listOf(news90),
            legacyEpisodes = listOf(stale),
            limit = 20,
        ).single()

        assertEquals(ServusBranding.NEWS_90_SECONDS_SHOW_ID, result.showId)
        assertEquals(ServusBranding.NEWS_90_SECONDS_LOGO_URI, result.logoUri)
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

    @Test
    fun selectedShowWithoutTimestampCannotBePushedOutByTimestampedNews() {
        val selectedEpisode = episode(
            id = "evening-newest",
            title = "Servus am Abend | 31.08.",
            showName = "Servus am Abend",
        ).copy(showId = "evening")
        val selectedShow = show("evening", "Servus am Abend", episodes = listOf(selectedEpisode))
        val news90 = show("news90", "Servus Nachrichten in 90 Sekunden")
        val legacy = (1..30).map { index ->
            episode(
                id = "news-$index",
                title = "Meldung $index",
                showName = "Servus Nachrichten in 90 Sekunden",
            ).copy(
                publishedAtMillis = 10_000_000L + index,
            )
        }

        val result = ServusCurrentChannelPolicy.composeCurrentEpisodes(
            selectedShows = listOf(news90, selectedShow),
            allShows = listOf(news90, selectedShow),
            legacyEpisodes = legacy,
            limit = 20,
        )

        assertEquals(20, result.size)
        assertTrue(result.any { it.id == "evening-newest" })
    }

    @Test
    fun observedAvailabilityParticipatesInCurrentOrdering() {
        val show = show(
            "evening",
            "Servus am Abend",
            episodes = listOf(
                episode("older", "Servus am Abend | 30.08.", "Servus am Abend")
                    .copy(showId = "evening", observedAvailableAtMillis = 1_000L),
                episode("newer", "Servus am Abend | 31.08.", "Servus am Abend")
                    .copy(showId = "evening", observedAvailableAtMillis = 2_000L),
            ),
        )

        val result = ServusCurrentChannelPolicy.composeCurrentEpisodes(
            selectedShows = listOf(show),
            allShows = listOf(show),
            legacyEpisodes = emptyList(),
            limit = 20,
        )

        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    private fun category(vararg shows: ServusShow) = ServusCategory(
        id = "category",
        title = "Kategorie",
        order = 0,
        shows = shows.toList(),
    )

    private fun show(
        id: String,
        title: String,
        episodes: List<ServusNewsEpisode> = emptyList(),
    ) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "category",
        categoryTitle = "Kategorie",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = null,
        episodes = episodes,
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
