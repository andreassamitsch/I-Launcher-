package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.data.tmdb.MediaLookup
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
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
            sourceKey = resolverSourceKey(media),
            lookup = MediaLookup(
                rawTitle = media.title,
                typeHint = resolverTypeHint(media),
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

/**
 * Android PreviewProgram.COLUMN_TYPE is provider supplied and is not reliable enough to hard-lock
 * TMDB to movie vs. series. Some apps publish every preview item as TYPE_MOVIE. Keep explicit
 * episode coordinates authoritative, but let the central resolver use multi-search for other
 * Preview Channel items. This stays provider-neutral and avoids a CloudStream-specific resolver.
 */
internal fun resolverTypeHint(media: MediaItem): MediaType = when {
    media.seasonNumber != null || media.episodeNumber != null -> MediaType.Episode
    media.source.provider == "android_preview_channel" -> MediaType.Unknown
    else -> media.type
}

/**
 * Version the Preview-Channel resolver cache key when the provider-type policy changes. Existing
 * Watch Next mappings keep their stable key; Preview Channels are resolved once with the new
 * provider-neutral multi-search policy instead of waiting for an old negative mapping to expire.
 */
internal fun resolverSourceKey(media: MediaItem): String =
    if (media.source.provider == "android_preview_channel") {
        "${media.source.sourceId}:preview-resolver-v2"
    } else {
        media.source.sourceId
    }
