package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaCollection
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaRelatedContent
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RELATIONS_TAG = "TMDB_RELATIONS"

class TmdbRelationsRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val cache = LinkedHashMap<String, CachedRelations>()

    suspend fun load(item: MediaItem): MediaRelatedContent = withContext(Dispatchers.IO) {
        val tmdbId = item.tmdbId ?: return@withContext MediaRelatedContent.Empty
        val relationType = when (item.type) {
            MediaType.Movie -> MediaType.Movie
            MediaType.Series,
            MediaType.Episode,
            -> MediaType.Series
            MediaType.Unknown -> return@withContext MediaRelatedContent.Empty
        }
        if (!network.isConfigured || tmdbId <= 0) return@withContext MediaRelatedContent.Empty

        val key = "${relationType.name}:$tmdbId"
        val now = System.currentTimeMillis()
        cache[key]
            ?.takeIf { now - it.updatedAtUtcMillis <= CACHE_MILLIS }
            ?.let { return@withContext it.content }

        val content = runCatching {
            val images = ensureImageConfiguration(now)
            val relations = when (relationType) {
                MediaType.Movie -> network.api.movieRelations(tmdbId, LANGUAGE)
                MediaType.Series -> network.api.tvRelations(tmdbId, LANGUAGE)
                else -> return@runCatching MediaRelatedContent.Empty
            }
            val similar = mapTmdbRelationItems(
                results = relations.similar?.results.orEmpty(),
                type = relationType,
                currentTmdbId = tmdbId,
                imageConfiguration = images,
                limit = SIMILAR_LIMIT,
            )
            val collection = if (relationType == MediaType.Movie) {
                loadMovieCollection(relations.belongsToCollection, tmdbId, images)
            } else {
                null
            }
            MediaRelatedContent(similar = similar, collection = collection)
        }.onFailure { throwable ->
            Log.w(RELATIONS_TAG, "TMDB relations failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(MediaRelatedContent.Empty)

        cache[key] = CachedRelations(content, now)
        trimCache()
        content
    }

    private suspend fun loadMovieCollection(
        reference: TmdbCollectionRefDto?,
        currentTmdbId: Int,
        images: TmdbImageConfiguration?,
    ): MediaCollection? {
        val collectionId = reference?.id?.takeIf { it > 0 } ?: return null
        return runCatching {
            val details = network.api.collectionDetails(collectionId, LANGUAGE)
            val title = details.name?.takeIf(String::isNotBlank)
                ?: reference.name?.takeIf(String::isNotBlank)
                ?: return@runCatching null
            val items = mapTmdbRelationItems(
                results = details.parts,
                type = MediaType.Movie,
                currentTmdbId = currentTmdbId,
                imageConfiguration = images,
                limit = COLLECTION_LIMIT,
            )
            if (items.isEmpty()) null else MediaCollection(title, items)
        }.onFailure { throwable ->
            Log.w(RELATIONS_TAG, "TMDB collection failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private suspend fun ensureImageConfiguration(now: Long): TmdbImageConfiguration? {
        imageConfigurationStore.loadFresh(now)?.let { return it }
        return runCatching {
            imageConfigurationStore.save(network.api.configuration().images, now)
        }.onFailure { throwable ->
            Log.w(RELATIONS_TAG, "TMDB relation image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun trimCache() {
        while (cache.size > MAX_CACHE_ENTRIES) {
            cache.keys.firstOrNull()?.let(cache::remove) ?: return
        }
    }

    private data class CachedRelations(
        val content: MediaRelatedContent,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val SIMILAR_LIMIT = 16
        const val COLLECTION_LIMIT = 24
        const val CACHE_MILLIS = 6L * 60L * 60L * 1_000L
        const val MAX_CACHE_ENTRIES = 36
    }
}

internal fun mapTmdbRelationItems(
    results: List<TmdbSearchResultDto>,
    type: MediaType,
    currentTmdbId: Int,
    imageConfiguration: TmdbImageConfiguration?,
    limit: Int,
): List<MediaItem> = results
    .asSequence()
    .filter { result -> result.id > 0 && result.id != currentTmdbId && !result.adult }
    .mapNotNull { result ->
        val displayTitle = when (type) {
            MediaType.Movie -> result.title
            MediaType.Series -> result.name
            else -> null
        }?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val originalTitle = when (type) {
            MediaType.Movie -> result.originalTitle
            MediaType.Series -> result.originalName
            else -> null
        }
        MediaItem(
            id = "tmdb:related:${type.name}:${result.id}",
            type = type,
            title = displayTitle,
            originalTitle = originalTitle,
            overview = result.overview,
            releaseYear = TmdbRepository.yearOf(result.releaseDate ?: result.firstAirDate),
            tmdbId = result.id,
            posterUri = imageConfiguration?.url(TmdbImageKind.Poster, result.posterPath),
            backdropUri = imageConfiguration?.url(TmdbImageKind.Backdrop, result.backdropPath),
            voteAverage = result.voteAverage.takeIf { it > 0.0 },
            source = MediaSource(
                provider = "tmdb_related",
                sourceId = "tmdb:${type.name}:${result.id}",
            ),
            resolverConfidence = 1f,
        )
    }
    .distinctBy(MediaItem::tmdbId)
    .take(limit)
    .toList()
