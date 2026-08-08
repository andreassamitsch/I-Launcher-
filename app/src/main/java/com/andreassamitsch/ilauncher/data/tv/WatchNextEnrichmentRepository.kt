package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.data.tmdb.MediaLookup
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository
import com.andreassamitsch.ilauncher.model.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class WatchNextEnrichmentRepository(
    private val tmdbRepository: TmdbRepository,
) {
    val isTmdbConfigured: Boolean
        get() = tmdbRepository.isConfigured

    fun base(items: List<com.andreassamitsch.ilauncher.model.WatchNextItem>): List<EnrichedWatchNextItem> =
        items.map { item ->
            EnrichedWatchNextItem(
                sourceItem = item,
                media = WatchNextMediaMapper.base(item),
            )
        }

    suspend fun enrich(
        baseItems: List<EnrichedWatchNextItem>,
    ): List<EnrichedWatchNextItem> {
        if (!isTmdbConfigured || baseItems.isEmpty()) return baseItems

        val semaphore = Semaphore(MAX_PARALLEL_LOOKUPS)
        return supervisorScope {
            baseItems.map { item ->
                async {
                    semaphore.withPermit {
                        enrichOne(item)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun enrichOne(item: EnrichedWatchNextItem): EnrichedWatchNextItem {
        val media = item.media
        val metadata = tmdbRepository.resolve(
            sourceKey = media.source.sourceId,
            lookup = MediaLookup(
                rawTitle = media.title,
                typeHint = media.type,
                releaseYear = media.releaseYear,
                seasonNumber = media.seasonNumber,
                episodeNumber = media.episodeNumber,
            ),
        ) ?: return item

        return item.copy(media = WatchNextMediaMapper.enrich(media, metadata))
    }

    companion object {
        private const val MAX_PARALLEL_LOOKUPS = 2
    }
}
