package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType

/** Global filters shared by Movies and Series discovery. */
data class TmdbDiscoveryFilterSettings(
    val hideAnime: Boolean = true,
    val kidsMode: Boolean = false,
)

/**
 * Launcher-owned policy for discovery relevance and safety filtering.
 *
 * TMDB's `with_genres` means that a title has the requested genre, not that the genre is its
 * primary identity. We therefore reject a few clearly conflicting genre mixes and softly re-rank
 * the remaining candidates so secondary Comedy/Family tags do not dominate more representative
 * titles. Anime filtering uses TMDB's anime keyword at the API level where possible and a
 * conservative Japanese-animation heuristic for feeds that do not support keyword exclusion.
 */
internal object TmdbDiscoveryContentPolicy {
    const val ANIME_KEYWORD_ID = "210024"
    const val ANIMATION_GENRE_ID = 16
    const val FAMILY_GENRE_ID = 10751
    const val KIDS_TV_GENRE_ID = 10762

    private const val COMEDY_GENRE_ID = 35
    private const val ACTION_GENRE_ID = 28
    private const val ADVENTURE_GENRE_ID = 12
    private const val FANTASY_GENRE_ID = 14
    private const val SCIENCE_FICTION_GENRE_ID = 878
    private const val DRAMA_GENRE_ID = 18
    private const val HORROR_GENRE_ID = 27
    private const val CRIME_GENRE_ID = 80
    private const val THRILLER_GENRE_ID = 53
    private const val MYSTERY_GENRE_ID = 9648
    private const val ROMANCE_GENRE_ID = 10749
    private const val WAR_GENRE_ID = 10752
    private const val HISTORY_GENRE_ID = 36
    private const val MUSIC_GENRE_ID = 10402

    private val movieKidsRowKeys = listOf(
        "movie-popular",
        "movie-top-rated",
        "movie-genre-10751",
        "movie-genre-16",
    )

    private val seriesKidsRowKeys = listOf(
        "series-popular",
        "series-top-rated",
        "series-genre-10762",
        "series-genre-10751",
        "series-genre-16",
    )

    fun effectiveRows(
        type: MediaType,
        requested: List<TmdbDiscoveryRowDefinition>,
        settings: TmdbDiscoveryFilterSettings,
    ): List<TmdbDiscoveryRowDefinition> {
        if (!settings.kidsMode) return requested
        val safeKeys = when (type) {
            MediaType.Movie -> movieKidsRowKeys
            MediaType.Series -> seriesKidsRowKeys
            else -> emptyList()
        }
        return TmdbDiscoveryCatalog.selectedRows(type, safeKeys)
    }

    fun allowAnimeForGenre(genreId: String?): Boolean =
        genreId?.toIntOrNull() == ANIMATION_GENRE_ID

    fun withoutKeywords(
        settings: TmdbDiscoveryFilterSettings,
        genreId: String?,
    ): String? = if (settings.hideAnime && !allowAnimeForGenre(genreId)) {
        ANIME_KEYWORD_ID
    } else {
        null
    }

    fun movieCertificationCountry(settings: TmdbDiscoveryFilterSettings): String? =
        "DE".takeIf { settings.kidsMode }

    fun movieCertificationLte(settings: TmdbDiscoveryFilterSettings): String? =
        "6".takeIf { settings.kidsMode }

    /**
     * Keep movie discovery tied to the German market even outside Kids Mode.
     *
     * TMDB's `language=de-DE` localizes metadata but does not filter availability. `region=DE`
     * instead requires a matching German release for discover results, which is a better proxy for
     * a German-language launcher than restricting `original_language` to German productions only.
     */
    fun movieRegion(settings: TmdbDiscoveryFilterSettings): String? = "DE"

    fun allowCategoryKindInKidsMode(kind: TmdbDiscoveryCategoryRowKind): Boolean = when (kind) {
        TmdbDiscoveryCategoryRowKind.TrendingDay,
        TmdbDiscoveryCategoryRowKind.TrendingWeek,
        TmdbDiscoveryCategoryRowKind.NowPlaying,
        TmdbDiscoveryCategoryRowKind.Upcoming,
        TmdbDiscoveryCategoryRowKind.AiringToday,
        TmdbDiscoveryCategoryRowKind.OnTheAir,
        -> false

        else -> true
    }

    fun prepareDiscoverResults(
        type: MediaType,
        results: List<TmdbSearchResultDto>,
        settings: TmdbDiscoveryFilterSettings,
        genreId: String?,
        movieCertificationApplied: Boolean,
    ): List<TmdbSearchResultDto> {
        val filtered = results.filter { result ->
            result.id > 0 &&
                !result.adult &&
                animeAllowed(result, settings, genreId) &&
                kidsAllowed(type, result, settings, movieCertificationApplied) &&
                genreScopeAllows(result, genreId)
        }
        return rankForGenre(filtered, genreId)
    }

    fun prepareFeedResults(
        type: MediaType,
        results: List<TmdbSearchResultDto>,
        settings: TmdbDiscoveryFilterSettings,
    ): List<TmdbSearchResultDto> = results.filter { result ->
        result.id > 0 &&
            !result.adult &&
            animeAllowed(result, settings, genreId = null) &&
            kidsAllowed(type, result, settings, movieCertificationApplied = false)
    }

    fun prepareSearchResults(
        results: List<TmdbSearchResultDto>,
        settings: TmdbDiscoveryFilterSettings,
    ): List<TmdbSearchResultDto> = results.filter { result ->
        val type = when (result.mediaType) {
            "movie" -> MediaType.Movie
            "tv" -> MediaType.Series
            else -> return@filter false
        }
        result.id > 0 &&
            !result.adult &&
            animeAllowed(result, settings, genreId = null) &&
            kidsAllowed(type, result, settings, movieCertificationApplied = false)
    }

    fun prepareRelatedResults(
        type: MediaType,
        results: List<TmdbSearchResultDto>,
        settings: TmdbDiscoveryFilterSettings,
        allowAnime: Boolean,
    ): List<TmdbSearchResultDto> = results.filter { result ->
        result.id > 0 &&
            !result.adult &&
            (!settings.hideAnime || allowAnime || !result.isLikelyAnime()) &&
            kidsAllowed(type, result, settings, movieCertificationApplied = false)
    }

    fun preparePersonCredits(
        credits: List<TmdbPersonCreditDto>,
        settings: TmdbDiscoveryFilterSettings,
    ): List<TmdbPersonCreditDto> = credits.filter { credit ->
        val type = when (credit.mediaType) {
            "movie" -> MediaType.Movie
            "tv" -> MediaType.Series
            else -> return@filter false
        }
        val likelyAnime = ANIMATION_GENRE_ID in credit.genreIds &&
            (credit.originalLanguage.equals("ja", ignoreCase = true) ||
                credit.originCountry.any { it.equals("JP", ignoreCase = true) })
        val animeAllowed = !settings.hideAnime || !likelyAnime
        val kidsAllowed = if (!settings.kidsMode) {
            true
        } else {
            when (type) {
                MediaType.Movie -> FAMILY_GENRE_ID in credit.genreIds
                MediaType.Series -> FAMILY_GENRE_ID in credit.genreIds || KIDS_TV_GENRE_ID in credit.genreIds
                else -> false
            }
        }
        !credit.adult && animeAllowed && kidsAllowed
    }

    fun rankForGenre(
        results: List<TmdbSearchResultDto>,
        genreId: String?,
    ): List<TmdbSearchResultDto> {
        val targetGenre = genreId?.toIntOrNull() ?: return results
        return results
            .mapIndexed { index, result ->
                RankedGenreResult(
                    result = result,
                    affinity = genreAffinityScore(result, targetGenre),
                    originalIndex = index,
                )
            }
            .sortedWith(
                compareByDescending<RankedGenreResult> { it.affinity }
                    .thenBy { it.originalIndex },
            )
            .map(RankedGenreResult::result)
    }

    fun diversifyCategorySections(
        sections: List<TmdbBrowseSection>,
        distinctLeadCount: Int = 8,
    ): List<TmdbBrowseSection> {
        if (sections.size < 2 || distinctLeadCount <= 0) return sections
        val usedLeadIds = mutableSetOf<Int>()
        return sections.map { section ->
            val unique = section.items.filter { item ->
                val id = item.tmdbId
                id == null || id !in usedLeadIds
            }
            val repeats = section.items.filter { item ->
                val id = item.tmdbId
                id != null && id in usedLeadIds
            }
            val reordered = (unique + repeats).distinctBy { it.tmdbId ?: it.id }
            reordered.take(distinctLeadCount).mapNotNullTo(usedLeadIds) { it.tmdbId }
            section.copy(items = reordered)
        }
    }

    private fun animeAllowed(
        result: TmdbSearchResultDto,
        settings: TmdbDiscoveryFilterSettings,
        genreId: String?,
    ): Boolean = !settings.hideAnime || allowAnimeForGenre(genreId) || !result.isLikelyAnime()

    private fun kidsAllowed(
        type: MediaType,
        result: TmdbSearchResultDto,
        settings: TmdbDiscoveryFilterSettings,
        movieCertificationApplied: Boolean,
    ): Boolean {
        if (!settings.kidsMode) return true
        return when (type) {
            MediaType.Movie -> movieCertificationApplied || FAMILY_GENRE_ID in result.genreIds
            MediaType.Series -> FAMILY_GENRE_ID in result.genreIds || KIDS_TV_GENRE_ID in result.genreIds
            else -> false
        }
    }

    private fun genreScopeAllows(
        result: TmdbSearchResultDto,
        genreId: String?,
    ): Boolean {
        if (genreId?.toIntOrNull() != COMEDY_GENRE_ID) return true
        val genres = result.genreIds.toSet()

        // TMDB can tag superhero/action-adventure titles as Comedy even when humour is secondary.
        val actionAdventureBlockbuster = ACTION_GENRE_ID in genres &&
            (ADVENTURE_GENRE_ID in genres ||
                FANTASY_GENRE_ID in genres ||
                SCIENCE_FICTION_GENRE_ID in genres)

        // Likewise, Crime + Thriller + Comedy commonly describes a crime/thriller with comic tones.
        val crimeThriller = CRIME_GENRE_ID in genres && THRILLER_GENRE_ID in genres

        return !actionAdventureBlockbuster && !crimeThriller
    }

    private fun TmdbSearchResultDto.isLikelyAnime(): Boolean =
        ANIMATION_GENRE_ID in genreIds &&
            (originalLanguage.equals("ja", ignoreCase = true) ||
                originCountry.any { it.equals("JP", ignoreCase = true) })

    private fun genreAffinityScore(result: TmdbSearchResultDto, targetGenre: Int): Int {
        val genres = result.genreIds.toSet()
        var score = if (result.genreIds.firstOrNull() == targetGenre) 1 else 0
        score += when (targetGenre) {
            COMEDY_GENRE_ID -> {
                listOf(
                    FAMILY_GENRE_ID to 6,
                    ANIMATION_GENRE_ID to 5,
                    ROMANCE_GENRE_ID to 2,
                    MUSIC_GENRE_ID to 1,
                    CRIME_GENRE_ID to -6,
                    THRILLER_GENRE_ID to -6,
                    HORROR_GENRE_ID to -8,
                    WAR_GENRE_ID to -5,
                    HISTORY_GENRE_ID to -3,
                    MYSTERY_GENRE_ID to -2,
                    ACTION_GENRE_ID to -1,
                    DRAMA_GENRE_ID to -1,
                ).sumOf { (genre, weight) -> if (genre in genres) weight else 0 }
            }

            FAMILY_GENRE_ID -> {
                listOf(
                    ANIMATION_GENRE_ID to 6,
                    ADVENTURE_GENRE_ID to 3,
                    COMEDY_GENRE_ID to 3,
                    FANTASY_GENRE_ID to 2,
                    HORROR_GENRE_ID to -10,
                    THRILLER_GENRE_ID to -6,
                    CRIME_GENRE_ID to -5,
                    WAR_GENRE_ID to -6,
                ).sumOf { (genre, weight) -> if (genre in genres) weight else 0 }
            }

            ACTION_GENRE_ID -> {
                listOf(
                    ADVENTURE_GENRE_ID to 3,
                    THRILLER_GENRE_ID to 2,
                    CRIME_GENRE_ID to 1,
                    COMEDY_GENRE_ID to -1,
                ).sumOf { (genre, weight) -> if (genre in genres) weight else 0 }
            }

            THRILLER_GENRE_ID -> {
                listOf(
                    MYSTERY_GENRE_ID to 3,
                    CRIME_GENRE_ID to 3,
                    ACTION_GENRE_ID to 1,
                    FAMILY_GENRE_ID to -6,
                    ANIMATION_GENRE_ID to -4,
                ).sumOf { (genre, weight) -> if (genre in genres) weight else 0 }
            }

            else -> 0
        }
        return score
    }

    private data class RankedGenreResult(
        val result: TmdbSearchResultDto,
        val affinity: Int,
        val originalIndex: Int,
    )
}
