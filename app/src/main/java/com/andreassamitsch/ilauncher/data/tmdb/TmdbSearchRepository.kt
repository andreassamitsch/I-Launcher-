package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.TrailerProvider
import com.andreassamitsch.ilauncher.model.TrailerRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SEARCH_TAG = "TMDB_SEARCH"

class TmdbSearchRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val queryCache = LinkedHashMap<String, CachedSearch>()
    private val detailsCache = LinkedHashMap<String, CachedDetails>()

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
            network.api.searchMulti(
                query = query.trim(),
                language = LANGUAGE,
                includeAdult = false,
                page = 1,
            ).results.mapNotNull { dto -> dto.toSearchMedia(images) }
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB global search failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(emptyList())

        queryCache[normalized] = CachedSearch(items, now)
        trimCache(queryCache, MAX_QUERY_CACHE_ENTRIES)
        items
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
            val images = ensureImageConfiguration(now)
            details.toDetailedMedia(item.type, images)
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB search details failed (${throwable.javaClass.simpleName})")
        }.getOrNull() ?: return@withContext null

        detailsCache[key] = CachedDetails(detailed, now)
        trimCache(detailsCache, MAX_DETAILS_CACHE_ENTRIES)
        detailed
    }

    private suspend fun ensureImageConfiguration(now: Long): TmdbImageConfiguration? {
        imageConfigurationStore.loadFresh(now)?.let { return it }
        return runCatching {
            imageConfigurationStore.save(network.api.configuration().images, now)
        }.onFailure { throwable ->
            Log.w(SEARCH_TAG, "TMDB image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun TmdbSearchResultDto.toSearchMedia(
        images: TmdbImageConfiguration?,
    ): MediaItem? {
        val type = when (mediaType) {
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
            releaseYear = TmdbRepository.yearOf(releaseDate ?: firstAirDate),
            tmdbId = id,
            posterUri = images?.url(TmdbImageKind.Poster, posterPath),
            backdropUri = images?.url(TmdbImageKind.Backdrop, backdropPath),
            source = MediaSource(
                provider = "tmdb_search",
                sourceId = "tmdb:${type.name}:$id",
            ),
        )
    }

    private fun TmdbMediaDetailsDto.toDetailedMedia(
        type: MediaType,
        images: TmdbImageConfiguration?,
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
        val logoPath = this.images?.logos
            .orEmpty()
            .sortedWith(
                compareBy<TmdbImageDto> { languageRank(it.language) }
                    .thenByDescending { it.voteAverage },
            )
            .firstOrNull()
            ?.filePath
        val trailerId = TmdbTrailerSelector.preferredYouTubeId(videos?.results.orEmpty())

        return MediaItem(
            id = "tmdb:${type.name}:$id",
            type = type,
            title = displayTitle,
            originalTitle = original,
            overview = overview,
            releaseYear = TmdbRepository.yearOf(releaseDate ?: firstAirDate),
            tmdbId = id,
            posterUri = images?.url(TmdbImageKind.Poster, posterPath),
            backdropUri = images?.url(TmdbImageKind.Backdrop, backdropPath),
            logoUri = images?.url(TmdbImageKind.Logo, logoPath),
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

    private fun <K, V> trimCache(cache: LinkedHashMap<K, V>, maxEntries: Int) {
        while (cache.size > maxEntries) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private data class CachedSearch(
        val items: List<MediaItem>,
        val updatedAtUtcMillis: Long,
    )

    private data class CachedDetails(
        val item: MediaItem,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val SEARCH_CACHE_MILLIS = 15L * 60L * 1_000L
        const val DETAILS_CACHE_MILLIS = 60L * 60L * 1_000L
        const val MAX_QUERY_CACHE_ENTRIES = 20
        const val MAX_DETAILS_CACHE_ENTRIES = 24
    }
}
