package com.andreassamitsch.ilauncher.ui.discover

import androidx.compose.runtime.staticCompositionLocalOf
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.data.tmdb.TmdbPeopleRepository
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRelationsRepository
import com.andreassamitsch.ilauncher.data.tmdb.TmdbSearchRepository
import com.andreassamitsch.ilauncher.data.tmdb.TmdbSeriesRepository
import com.andreassamitsch.ilauncher.model.MediaCredits
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaRelatedContent
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.PersonDetails
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import com.andreassamitsch.ilauncher.model.SeriesSeason
import com.andreassamitsch.ilauncher.model.SeriesSeasonContent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * UI-facing adapter for TMDB discovery, relations, series seasons and people details.
 *
 * Optional rows, card-detail prefetch, related content and person navigation stay lazy here while
 * the repositories own network/cache policy. Prefetching is bounded so fast D-Pad movement never
 * opens an unbounded burst of TMDB detail requests.
 */
class TmdbDiscoveryLoader(
    private val repository: TmdbSearchRepository,
    private val peopleRepository: TmdbPeopleRepository,
    private val relationsRepository: TmdbRelationsRepository,
    private val seriesRepository: TmdbSeriesRepository,
) {
    private val prefetchedDetails = ConcurrentHashMap<String, MediaItem>()
    private val prefetching = ConcurrentHashMap.newKeySet<String>()
    private val prefetchSemaphore = Semaphore(PREFETCH_CONCURRENCY)

    suspend fun browse(type: MediaType, rowKeys: List<String>): List<SearchBrowseSection> =
        repository.browse(type, rowKeys).map { section ->
            SearchBrowseSection(
                key = section.key,
                title = section.title,
                items = section.items.map(::toSearchItem),
            )
        }

    fun peekDetails(item: MediaItem): MediaItem? = detailKey(item)?.let(prefetchedDetails::get)

    suspend fun loadDetails(item: MediaItem): MediaItem {
        val key = detailKey(item)
        if (key != null) prefetchedDetails[key]?.let { return it }
        val detailed = repository.loadDetails(item) ?: item
        if (key != null) rememberPrefetched(key, detailed)
        return detailed
    }

    suspend fun prefetchDetails(items: List<MediaItem>) = coroutineScope {
        items
            .distinctBy(::detailKey)
            .map { item ->
                async {
                    val key = detailKey(item) ?: return@async
                    if (prefetchedDetails.containsKey(key) || !prefetching.add(key)) return@async
                    try {
                        prefetchSemaphore.withPermit {
                            repository.loadDetails(item)?.let { rememberPrefetched(key, it) }
                        }
                    } finally {
                        prefetching.remove(key)
                    }
                }
            }
            .awaitAll()
    }

    suspend fun loadRelated(item: MediaItem): MediaRelatedContent = relationsRepository.load(item)

    suspend fun loadCredits(item: MediaItem): MediaCredits = peopleRepository.loadCredits(item)

    suspend fun loadPerson(personId: Int): PersonDetails? = peopleRepository.loadPerson(personId)

    suspend fun loadSeriesSeasons(item: MediaItem): List<SeriesSeason> = seriesRepository.loadSeasons(item)

    suspend fun loadSeriesSeason(item: MediaItem, seasonNumber: Int): SeriesSeasonContent? =
        seriesRepository.loadSeason(item, seasonNumber)

    private fun rememberPrefetched(key: String, media: MediaItem) {
        prefetchedDetails[key] = media
        while (prefetchedDetails.size > MAX_PREFETCHED_DETAILS) {
            prefetchedDetails.keys.firstOrNull()?.let(prefetchedDetails::remove) ?: break
        }
    }

    private fun detailKey(item: MediaItem): String? {
        val tmdbId = item.tmdbId ?: return null
        return when (item.type) {
            MediaType.Movie -> "movie:$tmdbId"
            MediaType.Series -> "series:$tmdbId"
            else -> null
        }
    }

    private fun toSearchItem(media: MediaItem): SearchItem = SearchItem(
        id = "search:tmdb:${media.type}:${media.tmdbId}",
        kind = SearchResultKind.Tmdb,
        title = media.title,
        subtitle = media.releaseYear?.toString(),
        artworkUri = media.preferredArtworkUri,
        sourceLabel = "TMDB",
        media = media,
    )

    private companion object {
        const val PREFETCH_CONCURRENCY = 3
        const val MAX_PREFETCHED_DETAILS = 64
    }
}

val LocalTmdbDiscoveryLoader = staticCompositionLocalOf<TmdbDiscoveryLoader?> { null }
