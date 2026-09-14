package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType

enum class TmdbDiscoveryCategoryRowKind {
    TrendingDay,
    TrendingWeek,
    Popular,
    TopRated,
    RecentPopular,
    RecentTopRated,
    AllTimeTopRated,
    Classics,
    NowPlaying,
    Upcoming,
    AiringToday,
    OnTheAir,
}

data class TmdbDiscoveryCategoryRowDefinition(
    val key: String,
    val title: String,
    val kind: TmdbDiscoveryCategoryRowKind,
)

/**
 * Curated row templates for the "Mehr" page behind every Movies / Series discovery row.
 *
 * Genre rows keep their genre as the scope in the repository. Feed-style rows (trending,
 * popular, top rated, cinema / on-air) receive a small set of related views instead of simply
 * repeating another page of the same feed. The catalogue is launcher-owned and intentionally
 * compact so opening a category does not fan out into dozens of TMDB requests.
 */
object TmdbDiscoveryCategoryCatalog {
    fun rows(
        type: MediaType,
        parent: TmdbDiscoveryRowDefinition,
    ): List<TmdbDiscoveryCategoryRowDefinition> {
        if (type !in setOf(MediaType.Movie, MediaType.Series)) return emptyList()

        val kinds = when {
            parent.kind == TmdbDiscoveryRowKind.Genre -> GENRE_KINDS
            parent.kind == TmdbDiscoveryRowKind.Trending -> TRENDING_KINDS
            parent.kind == TmdbDiscoveryRowKind.Popular -> POPULAR_KINDS
            parent.kind == TmdbDiscoveryRowKind.TopRated -> TOP_RATED_KINDS
            parent.kind == TmdbDiscoveryRowKind.NowPlaying -> MOVIE_NOW_PLAYING_KINDS
            parent.kind == TmdbDiscoveryRowKind.Upcoming -> MOVIE_UPCOMING_KINDS
            parent.kind == TmdbDiscoveryRowKind.AiringToday -> SERIES_AIRING_TODAY_KINDS
            parent.kind == TmdbDiscoveryRowKind.OnTheAir -> SERIES_ON_THE_AIR_KINDS
            else -> GENRE_KINDS
        }

        return kinds
            .filter { kind -> kind.isSupported(type) }
            .distinct()
            .map { kind ->
                TmdbDiscoveryCategoryRowDefinition(
                    key = "${parent.key}:more:${kind.keyPart}",
                    title = "${parent.title} · ${kind.title(type)}",
                    kind = kind,
                )
            }
    }

    private fun TmdbDiscoveryCategoryRowKind.isSupported(type: MediaType): Boolean = when (this) {
        TmdbDiscoveryCategoryRowKind.NowPlaying,
        TmdbDiscoveryCategoryRowKind.Upcoming,
        -> type == MediaType.Movie

        TmdbDiscoveryCategoryRowKind.AiringToday,
        TmdbDiscoveryCategoryRowKind.OnTheAir,
        -> type == MediaType.Series

        else -> true
    }

    private val TmdbDiscoveryCategoryRowKind.keyPart: String
        get() = when (this) {
            TmdbDiscoveryCategoryRowKind.TrendingDay -> "trending-day"
            TmdbDiscoveryCategoryRowKind.TrendingWeek -> "trending-week"
            TmdbDiscoveryCategoryRowKind.Popular -> "popular"
            TmdbDiscoveryCategoryRowKind.TopRated -> "top-rated"
            TmdbDiscoveryCategoryRowKind.RecentPopular -> "recent-popular"
            TmdbDiscoveryCategoryRowKind.RecentTopRated -> "recent-top-rated"
            TmdbDiscoveryCategoryRowKind.AllTimeTopRated -> "all-time"
            TmdbDiscoveryCategoryRowKind.Classics -> "classics"
            TmdbDiscoveryCategoryRowKind.NowPlaying -> "now-playing"
            TmdbDiscoveryCategoryRowKind.Upcoming -> "upcoming"
            TmdbDiscoveryCategoryRowKind.AiringToday -> "airing-today"
            TmdbDiscoveryCategoryRowKind.OnTheAir -> "on-the-air"
        }

    private fun TmdbDiscoveryCategoryRowKind.title(type: MediaType): String = when (this) {
        TmdbDiscoveryCategoryRowKind.TrendingDay -> "Heute im Trend"
        TmdbDiscoveryCategoryRowKind.TrendingWeek -> "Diese Woche im Trend"
        TmdbDiscoveryCategoryRowKind.Popular -> "Publikumslieblinge"
        TmdbDiscoveryCategoryRowKind.TopRated -> "Top bewertet"
        TmdbDiscoveryCategoryRowKind.RecentPopular -> "Neu & gefragt"
        TmdbDiscoveryCategoryRowKind.RecentTopRated -> "Neu & sehenswert"
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated -> "Allzeit-Favoriten"
        TmdbDiscoveryCategoryRowKind.Classics -> "Klassiker, die man gesehen haben sollte"
        TmdbDiscoveryCategoryRowKind.NowPlaying -> "Jetzt im Kino"
        TmdbDiscoveryCategoryRowKind.Upcoming -> "Demnächst im Kino"
        TmdbDiscoveryCategoryRowKind.AiringToday -> "Heute im TV"
        TmdbDiscoveryCategoryRowKind.OnTheAir -> if (type == MediaType.Series) "Aktuell laufend" else "Aktuell"
    }

    private val GENRE_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.TopRated,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
        TmdbDiscoveryCategoryRowKind.Classics,
    )

    private val TRENDING_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.TrendingDay,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
        TmdbDiscoveryCategoryRowKind.Classics,
    )

    private val POPULAR_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.TopRated,
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
        TmdbDiscoveryCategoryRowKind.Classics,
    )

    private val TOP_RATED_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.TopRated,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.Classics,
    )

    private val MOVIE_NOW_PLAYING_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.NowPlaying,
        TmdbDiscoveryCategoryRowKind.TrendingDay,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.Upcoming,
    )

    private val MOVIE_UPCOMING_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.Upcoming,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.Popular,
        TmdbDiscoveryCategoryRowKind.AllTimeTopRated,
    )

    private val SERIES_AIRING_TODAY_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.AiringToday,
        TmdbDiscoveryCategoryRowKind.OnTheAir,
        TmdbDiscoveryCategoryRowKind.TrendingDay,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.TopRated,
    )

    private val SERIES_ON_THE_AIR_KINDS = listOf(
        TmdbDiscoveryCategoryRowKind.OnTheAir,
        TmdbDiscoveryCategoryRowKind.AiringToday,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.RecentPopular,
        TmdbDiscoveryCategoryRowKind.RecentTopRated,
        TmdbDiscoveryCategoryRowKind.TopRated,
    )
}
