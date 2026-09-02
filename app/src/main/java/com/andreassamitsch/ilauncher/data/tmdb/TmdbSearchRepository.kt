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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val discoveryPreferences = TmdbDiscoveryPreferences(appContext)
    private val queryCache = LinkedHashMap<SearchCacheKey, CachedSearch>()
    private val detailsCache = LinkedHashMap<String, CachedDetails>()
    private val browseCache = LinkedHashMap<BrowseCacheKey, CachedBrowse>()
    private val categoryBrowseCache = LinkedHashMap<CategoryBrowseCacheKey, CachedBrowse>()
    private val germanTranslationCache = LinkedHashMap<Int, CachedGermanTranslation>()
    private val germanTranslationCacheLock = Any()
    private val germanTranslationLookupSemaphore = Semaphore(GERMAN_TRANSLATION_MAX_CONCURRENCY)

    val isConfigured: Boolean
        get() = network.isConfigured

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase()
        if (!network.isConfigured || normalized.length < 3) return@withContext emptyList()
        val filterSettings = discoveryPreferences.filterSettings()
        val cacheKey = SearchCacheKey(normalized, filterSettings)
        val now = System.currentTimeMillis()
        queryCache[cacheKey]
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
            val filtered = TmdbDiscoveryContentPolicy.prepareSearchResults(
                response.results,
                filterSettings,
            )
            TmdbSearchRanker.rank(query.trim(), filtered)
                .mapNotNull { dto -> dto.toSearchMedia(images) }
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB global search failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(emptyList())

        queryCache[cacheKey] = CachedSearch(items, now)
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
        val filterSettings = discoveryPreferences.filterSettings()
        val selected = TmdbDiscoveryCatalog.selectedRows(type, rowKeys)
            .ifEmpty {
                TmdbDiscoveryCatalog.selectedRows(type, TmdbDiscoveryCatalog.defaultRowKeys(type))
            }
        val requestedRows = TmdbDiscoveryContentPolicy.effectiveRows(type, selected, filterSettings)
        if (requestedRows.isEmpty()) return@withContext emptyList()

        val cacheKey = BrowseCacheKey(
            type = type,
            rowKeys = requestedRows.map(TmdbDiscoveryRowDefinition::key),
            filterSettings = filterSettings,
        )
        val now = System.currentTimeMillis()
        browseCache[cacheKey]
            ?.takeIf { now - it.updatedAtUtcMillis <= BROWSE_CACHE_MILLIS }
            ?.let { return@withContext it.sections }

        val sections = runCatching {
            val imageConfig = ensureImageConfiguration(now)
            coroutineScope {
                requestedRows
                    .map { definition ->
                        definition to async {
                            loadDiscoveryRow(type, definition, imageConfig, filterSettings)
                        }
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
        val filterSettings = discoveryPreferences.filterSettings()
        val sourceDefinition = TmdbDiscoveryCatalog.rows(type)
            .firstOrNull { it.key == sourceRowKey }
            ?: return@withContext emptyList()
        if (filterSettings.kidsMode) {
            val safeRows = TmdbDiscoveryContentPolicy.effectiveRows(
                type = type,
                requested = TmdbDiscoveryCatalog.rows(type),
                settings = filterSettings,
            )
            if (safeRows.none { it.key == sourceRowKey }) return@withContext emptyList()
        }
        val requestedRows = TmdbDiscoveryCategoryCatalog.rows(type, sourceDefinition)
            .filter { definition ->
                !filterSettings.kidsMode ||
                    TmdbDiscoveryContentPolicy.allowCategoryKindInKidsMode(definition.kind)
            }
        if (requestedRows.isEmpty()) return@withContext emptyList()

        val cacheKey = CategoryBrowseCacheKey(type, sourceRowKey, filterSettings)
        val now = System.currentTimeMillis()
        categoryBrowseCache[cacheKey]
            ?.takeIf { now - it.updatedAtUtcMillis <= BROWSE_CACHE_MILLIS }
            ?.let { return@withContext it.sections }

        val sections = runCatching {
            val imageConfig = ensureImageConfiguration(now)
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            val loaded = coroutineScope {
                requestedRows
                    .map { definition ->
                        definition to async {
                            loadCategoryRow(
                                type = type,
                                sourceDefinition = sourceDefinition,
                                definition = definition,
                                imageConfig = imageConfig,
                                todayUtc = todayUtc,
                                filterSettings = filterSettings,
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
            TmdbDiscoveryContentPolicy.diversifyCategorySections(loaded)
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
        filterSettings: TmdbDiscoveryFilterSettings,
    ): List<MediaItem> = when (definition.kind) {
        TmdbDiscoveryRowKind.Trending -> loadTrending(
            type = type,
            imageConfig = imageConfig,
            timeWindow = definition.trendWindow.apiValue,
            filterSettings = filterSettings,
        )
        TmdbDiscoveryRowKind.Popular -> loadDiscover(
            type = type,
            imageConfig = imageConfig,
            sortBy = SORT_POPULARITY,
            genreId = null,
            voteCountGte = 0,
            filterSettings = filterSettings,
        )
        TmdbDiscoveryRowKind.TopRated -> loadDiscover(
            type = type,
            imageConfig = imageConfig,
            sortBy = SORT_RATING,
            genreId = null,
            voteCountGte = if (type == MediaType.Movie) 300 else 200,
            filterSettings = filterSettings,
        )
        TmdbDiscoveryRowKind.NowPlaying -> if (type == MediaType.Movie) {
            val raw = network.api.nowPlayingMovies(language = LANGUAGE).results
            TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                .toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.Upcoming -> if (type == MediaType.Movie) {
            val raw = network.api.upcomingMovies(language = LANGUAGE).results
            TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                .toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.AiringToday -> if (type == MediaType.Series) {
            val raw = network.api.airingTodayTv(language = LANGUAGE).results
            TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                .toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.OnTheAir -> if (type == MediaType.Series) {
            val raw = network.api.onTheAirTv(language = LANGUAGE).results
            TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                .toMediaItems(imageConfig, type)
        } else emptyList()
        TmdbDiscoveryRowKind.Genre -> loadDiscover(
            type = type,
            imageConfig = imageConfig,
            sortBy = SORT_POPULARITY,
            genreId = definition.genreId,
            voteCountGte = CATEGORY_MIN_VOTE_COUNT,
            filterSettings = filterSettings,
        )
    }

    private suspend fun loadCategoryRow(
        type: MediaType,
        sourceDefinition: TmdbDiscoveryRowDefinition,
        definition: TmdbDiscoveryCategoryRowDefinition,
        imageConfig: TmdbImageConfiguration?,
        todayUtc: LocalDate,
        filterSettings: TmdbDiscoveryFilterSettings,
    ): List<MediaItem> {
        val genreId = sourceDefinition.genreId
        val hasGenreScope = genreId != null
        val standardTopRatedVotes = when (type) {
            MediaType.Movie -> if (hasGenreScope) 500 else 300
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
                loadTrending(type, imageConfig, TmdbTrendWindow.Day.apiValue, filterSettings)

            TmdbDiscoveryCategoryRowKind.TrendingWeek ->
                loadTrending(type, imageConfig, TmdbTrendWindow.Week.apiValue, filterSettings)

            TmdbDiscoveryCategoryRowKind.Popular -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_VOTE_COUNT,
                genreId = genreId,
                voteCountGte = if (hasGenreScope) CATEGORY_MIN_VOTE_COUNT else 0,
                filterSettings = filterSettings,
            )

            TmdbDiscoveryCategoryRowKind.TopRated -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = standardTopRatedVotes,
                filterSettings = filterSettings,
            )

            TmdbDiscoveryCategoryRowKind.RecentPopular -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_POPULARITY,
                genreId = genreId,
                voteCountGte = recentVotes,
                releaseDateGte = todayUtc.minusYears(2).toString(),
                releaseDateLte = todayUtc.toString(),
                filterSettings = filterSettings,
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
                filterSettings = filterSettings,
            )

            TmdbDiscoveryCategoryRowKind.AllTimeTopRated -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_VOTE_COUNT,
                genreId = genreId,
                voteCountGte = allTimeVotes,
                voteAverageGte = ALL_TIME_MIN_RATING,
                releaseDateLte = todayUtc.minusYears(3).toString(),
                filterSettings = filterSettings,
            )

            TmdbDiscoveryCategoryRowKind.Classics -> loadDiscover(
                type = type,
                imageConfig = imageConfig,
                sortBy = SORT_RATING,
                genreId = genreId,
                voteCountGte = classicVotes,
                voteAverageGte = CLASSIC_MIN_RATING,
                releaseDateLte = todayUtc.minusYears(10).toString(),
                filterSettings = filterSettings,
            )

            TmdbDiscoveryCategoryRowKind.NowPlaying -> if (type == MediaType.Movie) {
                val raw = network.api.nowPlayingMovies(language = LANGUAGE).results
                TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                    .toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.Upcoming -> if (type == MediaType.Movie) {
                val raw = network.api.upcomingMovies(language = LANGUAGE).results
                TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                    .toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.AiringToday -> if (type == MediaType.Series) {
                val raw = network.api.airingTodayTv(language = LANGUAGE).results
                TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                    .toMediaItems(imageConfig, type)
            } else emptyList()

            TmdbDiscoveryCategoryRowKind.OnTheAir -> if (type == MediaType.Series) {
                val raw = network.api.onTheAirTv(language = LANGUAGE).results
                TmdbDiscoveryContentPolicy.prepareFeedResults(type, raw, filterSettings)
                    .toMediaItems(imageConfig, type)
            } else emptyList()
        }
    }

    private suspend fun loadTrending(
        type: MediaType,
        imageConfig: TmdbImageConfiguration?,
        timeWindow: String,
        filterSettings: TmdbDiscoveryFilterSettings,
    ): List<MediaItem> {
        val results = when (type) {
            MediaType.Movie -> network.api.trendingMovies(timeWindow = timeWindow, language = LANGUAGE).results
            MediaType.Series -> network.api.trendingTv(timeWindow = timeWindow, language = LANGUAGE).results
            else -> emptyList()
        }
        return TmdbDiscoveryContentPolicy.prepareFeedResults(type, results, filterSettings)
            .toMediaItems(imageConfig, type)
    }

    private suspend fun loadDiscover(
        type: MediaType,
        imageConfig: TmdbImageConfiguration?,
        sortBy: String,
        genreId: String?,
        voteCountGte: Int,
        filterSettings: TmdbDiscoveryFilterSettings,
        voteAverageGte: Double? = null,
        releaseDateGte: String? = null,
        releaseDateLte: String? = null,
    ): List<MediaItem> {
        val movieCertificationApplied = filterSettings.kidsMode && type == MediaType.Movie
        val results = when (type) {
            MediaType.Movie -> network.api.discoverMovies(
                language = LANGUAGE,
                sortBy = sortBy,
                withGenres = genreId,
                withoutKeywords = TmdbDiscoveryContentPolicy.withoutKeywords(filterSettings, genreId),
                voteCountGte = voteCountGte,
                voteAverageGte = voteAverageGte,
                primaryReleaseDateGte = releaseDateGte,
                primaryReleaseDateLte = releaseDateLte,
                region = TmdbDiscoveryContentPolicy.movieRegion(filterSettings),
                certificationCountry = TmdbDiscoveryContentPolicy.movieCertificationCountry(filterSettings),
                certificationLte = TmdbDiscoveryContentPolicy.movieCertificationLte(filterSettings),
            ).results
            MediaType.Series -> network.api.discoverTv(
                language = LANGUAGE,
                sortBy = sortBy,
                withGenres = genreId,
                withoutKeywords = TmdbDiscoveryContentPolicy.withoutKeywords(filterSettings, genreId),
                voteCountGte = voteCountGte,
                voteAverageGte = voteAverageGte,
                firstAirDateGte = releaseDateGte,
                firstAirDateLte = releaseDateLte,
            ).results
            else -> emptyList()
        }
        val prepared = TmdbDiscoveryContentPolicy.prepareDiscoverResults(
            type = type,
            results = results,
            settings = filterSettings,
            genreId = genreId,
            movieCertificationApplied = movieCertificationApplied,
        )
        val localized = if (type == MediaType.Movie) {
            keepMoviesWithVerifiedGermanTranslation(prepared)
        } else {
            prepared
        }
        return localized.toMediaItems(imageConfig, type)
    }

    private suspend fun keepMoviesWithVerifiedGermanTranslation(
        results: List<TmdbSearchResultDto>,
    ): List<TmdbSearchResultDto> = coroutineScope {
        results.map { result ->
            async {
                if (!TmdbDiscoveryContentPolicy.requiresGermanMovieTranslationLookup(result)) {
                    result
                } else if (hasGermanMovieTranslation(result.id)) {
                    result
                } else {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun hasGermanMovieTranslation(movieId: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(germanTranslationCacheLock) {
            germanTranslationCache[movieId]
                ?.takeIf { now - it.updatedAtUtcMillis <= GERMAN_TRANSLATION_CACHE_MILLIS }
                ?.let { return it.available }
        }

        return germanTranslationLookupSemaphore.withPermit {
            val refreshedNow = System.currentTimeMillis()
            synchronized(germanTranslationCacheLock) {
                germanTranslationCache[movieId]
                    ?.takeIf { refreshedNow - it.updatedAtUtcMillis <= GERMAN_TRANSLATION_CACHE_MILLIS }
                    ?.let { return@withPermit it.available }
            }

            val available = runCatching {
                TmdbDiscoveryContentPolicy.hasGermanMovieTranslation(
                    network.api.movieTranslations(movieId),
                )
            }.onFailure { throwable ->
                Log.w(
                    SEARCH_TAG,
                    "TMDB German translation lookup failed for movie $movieId (${throwable.javaClass.simpleName})",
                )
            }.getOrNull()

            if (available != null) {
                synchronized(germanTranslationCacheLock) {
                    germanTranslationCache[movieId] = CachedGermanTranslation(
                        available = available,
                        updatedAtUtcMillis = refreshedNow,
                    )
                    trimCache(germanTranslationCache, MAX_GERMAN_TRANSLATION_CACHE_ENTRIES)
                }
            }

            // A temporary lookup failure must not make an otherwise valid discovery row disappear.
            available ?: true
        }
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

    private data class SearchCacheKey(
        val query: String,
        val filterSettings: TmdbDiscoveryFilterSettings,
    )

    private data class BrowseCacheKey(
        val type: MediaType,
        val rowKeys: List<String>,
        val filterSettings: TmdbDiscoveryFilterSettings,
    )

    private data class CategoryBrowseCacheKey(
        val type: MediaType,
        val sourceRowKey: String,
        val filterSettings: TmdbDiscoveryFilterSettings,
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

    private data class CachedGermanTranslation(
        val available: Boolean,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val BROWSE_ROW_LIMIT = 16
        const val CATEGORY_MIN_VOTE_COUNT = 50
        const val SORT_POPULARITY = "popularity.desc"
        const val SORT_RATING = "vote_average.desc"
        const val SORT_VOTE_COUNT = "vote_count.desc"
        const val RECENT_QUALITY_MIN_RATING = 6.5
        const val ALL_TIME_MIN_RATING = 7.0
        const val CLASSIC_MIN_RATING = 7.0
        const val SEARCH_CACHE_MILLIS = 15L * 60L * 1_000L
        const val DETAILS_CACHE_MILLIS = 60L * 60L * 1_000L
        const val BROWSE_CACHE_MILLIS = 60L * 60L * 1_000L
        const val GERMAN_TRANSLATION_CACHE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val GERMAN_TRANSLATION_MAX_CONCURRENCY = 4
        const val MAX_QUERY_CACHE_ENTRIES = 20
        const val MAX_DETAILS_CACHE_ENTRIES = 24
        const val MAX_BROWSE_CACHE_ENTRIES = 6
        const val MAX_CATEGORY_BROWSE_CACHE_ENTRIES = 8
        const val MAX_GERMAN_TRANSLATION_CACHE_ENTRIES = 512
    }
}
