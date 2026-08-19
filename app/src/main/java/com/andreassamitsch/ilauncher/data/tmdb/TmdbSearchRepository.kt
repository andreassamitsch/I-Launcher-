package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.TrailerProvider
import com.andreassamitsch.ilauncher.model.TrailerRef
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val SEARCH_TAG = "TMDB_SEARCH"

internal fun TmdbMediaDetailsDto.preferredDiscoveryHeroBackdropPath(): String? {
    val primary = backdropPath?.takeIf(String::isNotBlank)
    val alternate = images?.backdrops
        .orEmpty()
        .filter { !it.filePath.isNullOrBlank() && it.filePath != primary }
        .sortedWith(
            compareBy<TmdbImageDto> { discoveryBackdropLanguageRank(it.language) }
                .thenByDescending { it.voteAverage },
        )
        .firstOrNull()
        ?.filePath
    return alternate ?: primary
}

private fun discoveryBackdropLanguageRank(language: String?): Int = when (language) {
    null -> 0
    "de" -> 1
    "en" -> 2
    else -> 3
}

class TmdbSearchRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val queryCache = LinkedHashMap<String, CachedSearch>()
    private val detailsCache = LinkedHashMap<String, CachedDetails>()
    private val browseCache = LinkedHashMap<BrowseCacheKey, CachedBrowse>()
    private val categoryBrowseCache = LinkedHashMap<CategoryBrowseCacheKey, CachedBrowse>()

    val isConfigured: Boolean
        get() = network.isConfigured

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase()
        if (!network.isConfigured || normalized.length < 3) return@withContext emptyList()
        val now = System.currentTimeMillis()
        queryCache[normalized]
            ?.takeIf { now - it.updatedAtUtcMillis <= SEARCH_CACHE_MILLIS }
            ?.let { return@withContext it.items }

        val items = runCatching {
            val images = ensureImageConfiguration(now)
            val response = network.api.searchMulti(
                query = query.trim(),
                language = LANGUAGE,
                includeAdult = false,
                page = 1,
            )
            TmdbSearchRanker.rank(query.trim(), response.results)
                .mapNotNull { dto -> dto.toSearchMedia(images) }
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB global search failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(emptyList())

        queryCache[normalized] = CachedSearch(items, now)
        trimCache(queryCache, MAX_QUERY_CACHE_ENTRIES)
        items
    }

    suspend fun browse(
        type: MediaType,
        rowKeys: List<String> = TmdbDiscoveryCatalog.defaultRowKeys(type),
    ): List<TmdbBrowseSection> = withContext(Dispatchers.IO) {
        if (!network.isConfigured || type !in setOf(MediaType.Movie, MediaType.Series)) {
            return@withContext emptyList()
        }
        val requestedRows = TmdbDiscoveryCatalog.selectedRows(type, rowKeys)
            .ifEmpty {
                TmdbDiscoveryCatalog.selectedRows(type, TmdbDiscoveryCatalog.defaultRowKeys(type))
            }
        if (requestedRows.isEmpty()) return@withContext emptyList()

        val cacheKey = BrowseCacheKey(type, requestedRows.map(TmdbDiscoveryRowDefinition::key))
        val now = System.currentTimeMillis()
        browseCache[cacheKey]
            ?.takeIf { now - it.updatedAtUtcMillis <= BROWSE_CACHE_MILLIS }
            ?.let { return@withContext it.sections }

        val sections = runCatching {
            val imageConfig = ensureImageConfiguration(now)
            coroutineScope {
                requestedRows
                    .map { definition ->
                        definition to async { loadDiscoveryRow(type, definition, imageConfig) }
                    }
                    .mapNotNull { (definition, deferred) ->
                        val rowItems = deferred.await().take(BROWSE_ROW_LIMIT)
                        if (rowItems.isEmpty()) null else TmdbBrowseSection(
                            key = definition.key,
                            title = definition.title,
                            items = rowItems,
                        )
                    }
            }
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB ${type.cacheKey()} discovery failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(emptyList())

        browseCache[cacheKey] = CachedBrowse(sections, now)
        trimCache(browseCache, MAX_BROWSE_CACHE_ENTRIES)
        sections
    }

    suspend fun browseCategory(
        type: MediaType,
        sourceRowKey: String,
    ): List<TmdbBrowseSection> = withContext(Dispatchers.IO) {
        if (!network.isConfigured || type !in setOf(MediaType.Movie, MediaType.Series)) {
            return@withContext emptyList()
        }
        val sourceDefinition = TmdbDiscoveryCatalog.rows(type)
            .firstOrNull { it.key == sourceRowKey }
            ?: return@withContext emptyList()
        val requestedRows = TmdbDiscoveryCategoryCatalog.rows(type, sourceDefinition)
        if (requestedRows.isEmpty()) return@withContext emptyList()

        val cacheKey = CategoryBrowseCacheKey(type, sourceRowKey)
        val now = System.currentTimeMillis()
        categoryBrowseCache[cacheKey]
            ?.takeIf { now - it.updatedAtUtcMillis <= BROWSE_CACHE_MILLIS }
            ?.let { return@withContext it.sections }

        val sections = runCatching {
            val imageConfig = ensureImageConfiguration(now)
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            coroutineScope {
                requestedRows
                    .map { definition ->
                        definition to async {
                            loadCategoryRow(
                                type = type,
                                sourceDefinition = sourceDefinition,
                                definition = definition,
                                imageConfig = imageConfig,
                                todayUtc = todayUtc,
                            )
                        }
                    }
                    .mapNotNull { (definition, deferred) ->
                        val rowItems = deferred.await()
                            .distinctBy { it.tmdbId ?: it.id }
                            .take(BROWSE_ROW_LIMIT)
                        if (rowItems.isEmpty()) null else TmdbBrowseSection(
                            key = definition.key,
                            title = definition.title,
                            items = rowItems,
                        )
                    }
            }
        }.onFailure { throwable ->
            Log.w(
                SEARCH_TAG,
                "TMDB ${type.cacheKey()} category discovery failed (${throwable.javaClass.simpleName})",
            )
        }.getOrDefault(emptyList())

        categoryBrowseCache[cacheKey] = CachedBrowse(sections, now)
        trimCache(categoryBrowseCache, MAX_CATEGORY_BROWSE_CACHE_ENTRIES)
        sections
    }

    suspend fun browse(): List<TmdbBrowseSection> {
        val series = browse(MediaType.Series)
        val movies = browse(MediaType.Movie)
        return series.take(2) + movies.take(2)
    }

    suspend fun loadDetails(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        val tmdbId = item.tmdbId ?: return@withContext null
        if (!network.isConfigured || item.type !in setOf(MediaType.Movie, MediaType.Series)) {
            return@withContext null
        }
        val key = "${item.type}:$tmdbId"
        val now = System.currentTimeMillis()
        detailsCache[key]
            ?.takeIf { now - it.updatedAtUtcMillis <= DETAILS_CACHE_MILLIS }
            ?.let { return@withContext it.item }

        val detailed = runCatching {
            val details = when (item.type) {
                MediaType.Movie -> network.api.movieDetails(tmdbId, LANGUAGE)
                MediaType.Series -> network.api.tvDetails(tmdbId, LANGUAGE)
                else -> return@runCatching item
            }
            val imageConfig = ensureImageConfiguration(now)
            details.toDetailedMedia(item.type, imageConfig)
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB search details failed (${throwable.javaClass.simpleName})")
        }.getOrNull() ?: return@withContext null

        detailsCache[key] = CachedDetails(detailed, now)
        trimCache(detailsCache, MAX_DETAILS_CACHE_ENTRIES)
        detailed
    }

    private suspend fun loadDiscoveryRow(
        type: MediaType,
        definition: TmdbDiscoveryRowDefinition,
        imageConfig: TmdbImageConfiguration?,
    ): List<MediaItem> = when (definition.kind) {
        TmdbDiscoveryRowKind.Trending -> loadTrending(type, imageConfig, definition.trendWindow.apiValue)
        TmdbDiscoveryRowKind.Popular -> loadDiscover(type, imageConfig, SORT_POPULARITY, null, 0)
        TmdbDiscoveryRowKind.TopRated -> loadDiscover(
            type,
            imageConfig,
            SORT_RATING,
            null,
            if (type == MediaType.Movie) 300 else 200,
        )
        TmdbDiscoveryRowKind.NowPlaying -> if (type == MediaType.Movie) {
            network.api.nowPlayingMovies(language = LANGUAGE).results.toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.Upcoming -> if (type == MediaType.Movie) {
            network.api.upcomingMovies(language = LANGUAGE).results.toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.AiringToday -> if (type == MediaType.Series) {
            network.api.airingTodayTv(language = LANGUAGE).results.toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.OnTheAir -> if (type == MediaType.Series) {
            network.api.onTheAirTv(language = LANGUAGE).results.toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.Genre -> loadDiscover(
            type,
            imageConfig,
            SORT_POPULARITY,
            definition.genreId,
            CATEGORY_MIN_VOTE_COUNT,
        )
    }

    private suspend fun loadCategoryRow(
        type: MediaType,
        sourceDefinition: TmdbDiscoveryRowDefinition,
        definition: TmdbDiscoveryCategoryRowDefinition,
        imageConfig: TmdbImageConfiguration?,
        todayUtc: LocalDate,
    ): List<MediaItem> {
        val genreId = sourceDefinition.genreId
        val hasGenreScope = genreId != null
        val standardTopRatedVotes = when (type) {
            MediaType.Movie -> if (hasGenreScope) 150 else 300
            MediaType.Series -> if (hasGenreScope) 80 else 200
            else -> 0
        }
        val recentVotes = when (type) {
            MediaType.Movie -> if (hasGenreScope) 60 else 120
            MediaType.Series -> if (hasGenreScope) 40 else 80
            else -> 0
        }
        val allTimeVotes = when (type) {
            MediaType.Movie -> if (hasGenreScope) 300 else 1_000
            MediaType.Series -> if (hasGenreScope) 150 else 500
            else -> 0
        }
        val classicVotes = when (type) {
            MediaType.Movie -> if (hasGenreScope) 180 else 600
            MediaType.Series -> if (hasGenreScope) 100 else 300
            else -> 0
        }

        return when (definition.kind) {
            TmdbDiscoveryCategoryRowKind.TrendingDay ->
                loadTrending(type, imageConfig, TmdbTrendWindow.Day.apiValue)

            TmdbDiscoveryCategoryRowKind.TrendingWeek ->
                loadTrending(type, imageConfig, TmdbTrendWindow.Week.apiValue)

            TmdbDiscoveryCategoryRowKind.Popular -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_POPULARITY,
                genreId = genreId,
                voteCountGte = if (hasGenreScope) CATEGORY_MIN_VOTE_COUNT else 0,
            )

            TmdbDiscoveryCategoryRowKind.TopRated -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = standardTopRatedVotes,
            )

            TmdbDiscoveryCategoryRowKind.RecentPopular -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_POPULARITY,
                genreId = genreId,
                voteCountGte = recentVotes,
                releaseDateGte = todayUtc.minusYears(2).toString(),
                releaseDateLte = todayUtc.toString(),
            )

            TmdbDiscoveryCategoryRowKind.RecentTopRated -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = recentVotes,
                voteAverageGte = RECENT_QUALITY_MIN_RATING,
                releaseDateGte = todayUtc.minusYears(5).toString(),
                releaseDateLte = todayUtc.toString(),
            )

            TmdbDiscoveryCategoryRowKind.AllTimeTopRated -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = allTimeVotes,
                voteAverageGte = ALL_TIME_MIN_RATING,
                releaseDateLte = todayUtc.toString(),
            )

            TmdbDiscoveryCategoryRowKind.Classics -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = classicVotes,
                voteAverageGte = CLASSIC_MIN_RATING,
                releaseDateLte = todayUtc.minusYears(10).toString(),
            )

            TmdbDiscoveryCategoryRowKind.NowPlaying -> if (type == MediaType.Movie) {
                network.api.nowPlayingMovies(language = LANGUAGE).results.toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.Upcoming -> if (type == MediaType.Movie) {
                network.api.upcomingMovies(language = LANGUAGE).results.toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.AiringToday -> if (type == MediaType.Series) {
                network.api.airingTodayTv(language = LANGUAGE).results.toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.OnTheAir -> if (type == MediaType.Series) {
                network.api.onTheAirTv(language = LANGUAGE).results.toMediaItems(imageConfig, type)
            } else emptyList()
        }
    }

    private suspend fun loadTrending(
        type: MediaType,
        imageConfig: TmdbImageConfiguration?,
        timeWindow: String,
    ): List<MediaItem> {
        val results = when (type) {
            MediaType.Movie -> network.api.trendingMovies(timeWindow = timeWindow, language = LANGUAGE).results
            MediaType.Series -> network.api.trendingTv(timeWindow = timeWindow, language = LANGUAGE).results
            else -> emptyList()
        }
        return results.toMediaItems(imageConfig, type)
    }

    private suspend fun loadDiscover(
        type: MediaType,
        imageConfig: TmdbImageConfiguration?,
        sortBy: String,
        genreId: String?,
        voteCountGte: Int,
        voteAverageGte: Double? = null,
        releaseDateGte: String? = null,
        releaseDateLte: String? = null,
    ): List<MediaItem> {
        val results = when (type) {
            MediaType.Movie -> network.api.discoverMovies(
                language = LANGUAGE,
                sortBy = sortBy,
                withGenres = genreId,
                voteCountGte = voteCountGte,
                voteAverageGte = voteAverageGte,
                primaryReleaseDateGte = releaseDateGte,
                primaryReleaseDateLte = releaseDateLte,
            ).results
            MediaType.Series -> network.api.discoverTv(
                language = LANGUAGE,
                sortBy = sortBy,
                withGenres = genreId,
                voteCountGte = voteCountGte,
                voteAverageGte = voteAverageGte,
                firstAirDateGte = releaseDateGte,
                firstAirDateLte = releaseDateLte,
            ).results
            else -> emptyList()
        }
        return results.toMediaItems(imageConfig, type)
    }

    private fun List<TmdbSearchResultDto>.toMediaItems(
        imageConfig: TmdbImageConfiguration?,
        type: MediaType,
    ): List<MediaItem> = mapNotNull { it.toSearchMedia(imageConfig, type) }

    private suspend fun ensureImageConfiguration(now: Long): TmdbImageConfiguration? {
        imageConfigurationStore.loadFresh(now)?.let { return it }
        return runCatching {
            imageConfigurationStore.save(network.api.configuration().images, now)
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun TmdbSearchResultDto.toSearchMedia(
        imageConfig: TmdbImageConfiguration?,
        forcedType: MediaType? = null,
    ): MediaItem? {
        val type = forcedType ?: when (mediaType) {
            "movie" -> MediaType.Movie
            "tv" -> MediaType.Series
            else -> return null
        }
        val displayTitle = when (type) {
            MediaType.Movie -> title
            MediaType.Series -> name
            else -> null
        }?.takeIf(String::isNotBlank) ?: return null
        val original = when (type) {
            MediaType.Movie -> originalTitle
            MediaType.Series -> originalName
            else -> null
        }
        return MediaItem(
            id = "tmdb:${type.name}:$id",
            type = type,
            title = displayTitle,
            originalTitle = original,
            overview = overview,
            releaseYear = TmdbRepository.yearOf(releaseDate ?: firstAirDate),
            tmdbId = id,
            posterUri = imageConfig?.url(TmdbImageKind.Poster, posterPath),
            backdropUri = imageConfig?.url(TmdbImageKind.Backdrop, backdropPath),
            voteAverage = voteAverage.takeIf { it > 0.0 },
            source = MediaSource(
                provider = "tmdb_search",
                sourceId = "tmdb:${type.name}:$id",
            ),
            resolverConfidence = 1f,
        )
    }

    private fun TmdbMediaDetailsDto.toDetailedMedia(
        type: MediaType,
        imageConfig: TmdbImageConfiguration?,
    ): MediaItem {
        val displayTitle = when (type) {
            MediaType.Movie -> title
            MediaType.Series -> name
            else -> null
        }.orEmpty()
        val original = when (type) {
            MediaType.Movie -> originalTitle
            MediaType.Series -> originalName
            else -> null
        }
        val logoPath = images?.logos
            .orEmpty()
            .sortedWith(
                compareBy<TmdbImageDto> { languageRank(it.language) }
                    .thenByDescending { it.voteAverage },
            )
            .firstOrNull()
            ?.filePath
        val heroBackdropPath = preferredDiscoveryHeroBackdropPath()
        val trailerId = TmdbTrailerSelector.preferredYouTubeId(videos?.results.orEmpty())

        return MediaItem(
            id = "tmdb:${type.name}:$id",
            type = type,
            title = displayTitle,
            originalTitle = original,
            overview = overview,
            releaseYear = TmdbRepository.yearOf(releaseDate ?: firstAirDate),
            tmdbId = id,
            posterUri = imageConfig?.url(TmdbImageKind.Poster, posterPath),
            backdropUri = imageConfig?.url(TmdbImageKind.Backdrop, backdropPath),
            heroBackdropUri = imageConfig?.url(TmdbImageKind.Backdrop, heroBackdropPath),
            logoUri = imageConfig?.url(TmdbImageKind.Logo, logoPath),
            durationMillis = (runtime ?: episodeRunTime?.firstOrNull())?.times(60_000L),
            voteAverage = voteAverage,
            imdbId = externalIds?.imdbId,
            tvdbId = externalIds?.tvdbId,
            wikidataId = externalIds?.wikidataId,
            trailer = trailerId?.let { TrailerRef(TrailerProvider.YouTube, it) },
            source = MediaSource(
                provider = "tmdb_search",
                sourceId = "tmdb:${type.name}:$id",
            ),
            resolverConfidence = 1f,
        )
    }

    private fun languageRank(language: String?): Int = when (language) {
        "de" -> 0
        "en" -> 1
        null -> 2
        else -> 3
    }

    private fun MediaType.cacheKey(): String = when (this) {
        MediaType.Movie -> "movie"
        MediaType.Series -> "series"
        else -> name.lowercase()
    }

    private fun <K, V> trimCache(cache: LinkedHashMap<K, V>, maxEntries: Int) {
        while (cache.size > maxEntries) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private data class BrowseCacheKey(
        val type: MediaType,
        val rowKeys: List<String>,
    )

    private data class CategoryBrowseCacheKey(
        val type: MediaType,
        val sourceRowKey: String,
    )

    private data class CachedSearch(
        val items: List<MediaItem>,
        val updatedAtUtcMillis: Long,
    )

    private data class CachedDetails(
        val item: MediaItem,
        val updatedAtUtcMillis: Long,
    )

    private data class CachedBrowse(
        val sections: List<TmdbBrowseSection>,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val BROWSE_ROW_LIMIT = 16
        const val CATEGORY_MIN_VOTE_COUNT = 50
        const val SORT_POPULARITY = "popularity.desc"
        const val SORT_RATING = "vote_average.desc"
        const val RECENT_QUALITY_MIN_RATING = 6.5
        const val ALL_TIME_MIN_RATING = 7.0
        const val CLASSIC_MIN_RATING = 7.0
        const val SEARCH_CACHE_MILLIS = 15L * 60L * 1_000L
        const val DETAILS_CACHE_MILLIS = 60L * 60L * 1_000L
        const val BROWSE_CACHE_MILLIS = 60L * 60L * 1_000L
        const val MAX_QUERY_CACHE_ENTRIES = 20
        const val MAX_DETAILS_CACHE_ENTRIES = 24
        const val MAX_BROWSE_CACHE_ENTRIES = 6
        const val MAX_CATEGORY_BROWSE_CACHE_ENTRIES = 8
    }
}
