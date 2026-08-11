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

        val enrichedMedia = enrichMedia(baseItems.map(EnrichedWatchNextItem::media))
            .associateBy { it.source.sourceId }
        return baseItems.map { item ->
            item.copy(media = enrichedMedia[item.media.source.sourceId] ?: item.media)
        }
    }

    /**
     * Provider-neutral TMDB enrichment for already normalized MediaItems.
     * Preview Channels use this only when the user explicitly enables TMDB for that channel.
     */
    suspend fun enrichMedia(baseItems: List<MediaItem>): List<MediaItem> {
        if (!isTmdbConfigured || baseItems.isEmpty()) return baseItems

        val semaphore = Semaphore(MAX_PARALLEL_LOOKUPS)
        return supervisorScope {
            baseItems.map { item ->
                async {
                    semaphore.withPermit {
                        enrichMediaOne(item)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun enrichMediaOne(media: MediaItem): MediaItem {
        if (!isTmdbConfigured) return media
        val metadata = tmdbRepository.resolve(
            sourceKey = media.source.sourceId,
            lookup = MediaLookup(
                rawTitle = media.title,
                typeHint = media.type,
                releaseYear = media.releaseYear,
                seasonNumber = media.seasonNumber,
                episodeNumber = media.episodeNumber,
            ),
        ) ?: return media

        return WatchNextMediaMapper.enrich(media, metadata)
    }

    companion object {
        private const val MAX_PARALLEL_LOOKUPS = 2
    }
}
