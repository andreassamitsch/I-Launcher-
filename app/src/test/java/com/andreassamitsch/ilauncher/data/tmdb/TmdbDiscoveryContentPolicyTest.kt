package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryContentPolicyTest {
    @Test
    fun comedyAffinityPrefersFamilyAnimationOverCrimeThrillerComedy() {
        val pulpLike = TmdbSearchResultDto(
            id = 1,
            title = "Crime comedy",
            genreIds = listOf(35, 80, 53, 18),
        )
        val toyStoryLike = TmdbSearchResultDto(
            id = 2,
            title = "Family animation comedy",
            genreIds = listOf(16, 12, 10751, 35),
        )

        val ranked = TmdbDiscoveryContentPolicy.rankForGenre(
            listOf(pulpLike, toyStoryLike),
            genreId = "35",
        )

        assertEquals(2, ranked.first().id)
        assertEquals(1, ranked.last().id)
    }

    @Test
    fun animeIsHiddenOutsideAnimationButKeptInsideAnimation() {
        val anime = TmdbSearchResultDto(
            id = 10,
            name = "Anime",
            genreIds = listOf(16, 10765),
            originalLanguage = "ja",
            originCountry = listOf("JP"),
        )
        val settings = TmdbDiscoveryFilterSettings(hideAnime = true)

        val normal = TmdbDiscoveryContentPolicy.prepareDiscoverResults(
            type = MediaType.Series,
            results = listOf(anime),
            settings = settings,
            genreId = "10765",
            movieCertificationApplied = false,
        )
        val animation = TmdbDiscoveryContentPolicy.prepareDiscoverResults(
            type = MediaType.Series,
            results = listOf(anime),
            settings = settings,
            genreId = "16",
            movieCertificationApplied = false,
        )

        assertTrue(normal.isEmpty())
        assertEquals(listOf(10), animation.map(TmdbSearchResultDto::id))
    }

    @Test
    fun japaneseLiveActionIsNotMisclassifiedAsAnime() {
        val liveAction = TmdbSearchResultDto(
            id = 11,
            title = "Japanese drama",
            genreIds = listOf(18),
            originalLanguage = "ja",
            originCountry = listOf("JP"),
        )

        val filtered = TmdbDiscoveryContentPolicy.prepareFeedResults(
            type = MediaType.Movie,
            results = listOf(liveAction),
            settings = TmdbDiscoveryFilterSettings(hideAnime = true),
        )

        assertEquals(listOf(11), filtered.map(TmdbSearchResultDto::id))
    }

    @Test
    fun kidsModeReplacesNormalRowsWithSafeDiscoveryRows() {
        val movieRows = TmdbDiscoveryContentPolicy.effectiveRows(
            type = MediaType.Movie,
            requested = TmdbDiscoveryCatalog.rows(MediaType.Movie),
            settings = TmdbDiscoveryFilterSettings(kidsMode = true),
        )
        val seriesRows = TmdbDiscoveryContentPolicy.effectiveRows(
            type = MediaType.Series,
            requested = TmdbDiscoveryCatalog.rows(MediaType.Series),
            settings = TmdbDiscoveryFilterSettings(kidsMode = true),
        )

        assertEquals(
            listOf("movie-popular", "movie-top-rated", "movie-genre-10751", "movie-genre-16"),
            movieRows.map(TmdbDiscoveryRowDefinition::key),
        )
        assertEquals(
            listOf(
                "series-popular",
                "series-top-rated",
                "series-genre-10762",
                "series-genre-10751",
                "series-genre-16",
            ),
            seriesRows.map(TmdbDiscoveryRowDefinition::key),
        )
    }

    @Test
    fun kidsModeUsesGermanMovieCertificationUpToFskSix() {
        val settings = TmdbDiscoveryFilterSettings(kidsMode = true)

        assertEquals("DE", TmdbDiscoveryContentPolicy.movieRegion(settings))
        assertEquals("DE", TmdbDiscoveryContentPolicy.movieCertificationCountry(settings))
        assertEquals("6", TmdbDiscoveryContentPolicy.movieCertificationLte(settings))
    }

    @Test
    fun kidsModeRejectsGeneralSeriesButKeepsFamilySeries() {
        val general = TmdbSearchResultDto(id = 20, name = "General", genreIds = listOf(18))
        val family = TmdbSearchResultDto(id = 21, name = "Family", genreIds = listOf(10751, 35))

        val filtered = TmdbDiscoveryContentPolicy.prepareDiscoverResults(
            type = MediaType.Series,
            results = listOf(general, family),
            settings = TmdbDiscoveryFilterSettings(kidsMode = true),
            genreId = null,
            movieCertificationApplied = false,
        )

        assertEquals(listOf(21), filtered.map(TmdbSearchResultDto::id))
    }

    @Test
    fun categoryDiversificationKeepsLeadingCardsDifferentAcrossRows() {
        val first = TmdbBrowseSection(
            key = "first",
            title = "First",
            items = (1..10).map(::media),
        )
        val second = TmdbBrowseSection(
            key = "second",
            title = "Second",
            items = (1..5).map(::media) + (11..15).map(::media),
        )

        val diversified = TmdbDiscoveryContentPolicy.diversifyCategorySections(
            sections = listOf(first, second),
            distinctLeadCount = 5,
        )

        assertEquals(listOf(1, 2, 3, 4, 5), diversified[0].items.take(5).mapNotNull(MediaItem::tmdbId))
        assertEquals(listOf(11, 12, 13, 14, 15), diversified[1].items.take(5).mapNotNull(MediaItem::tmdbId))
        assertFalse(diversified[1].items.take(5).any { it.tmdbId in 1..5 })
    }

    @Test
    fun animeKeywordExclusionIsDisabledForAnimationGenre() {
        val settings = TmdbDiscoveryFilterSettings(hideAnime = true)

        assertEquals(TmdbDiscoveryContentPolicy.ANIME_KEYWORD_ID, TmdbDiscoveryContentPolicy.withoutKeywords(settings, "35"))
        assertEquals(null, TmdbDiscoveryContentPolicy.withoutKeywords(settings, "16"))
    }

    private fun media(id: Int): MediaItem = MediaItem(
        id = "media:$id",
        type = MediaType.Movie,
        title = "Movie $id",
        tmdbId = id,
        source = MediaSource(provider = "test", sourceId = "test:$id"),
    )
}
