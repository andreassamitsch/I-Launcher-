package com.andreassamitsch.ilauncher.ui.discover

import androidx.compose.runtime.staticCompositionLocalOf
import com.andreassamitsch.ilauncher.data.search.SearchBrowseSection
import com.andreassamitsch.ilauncher.data.tmdb.TmdbSearchRepository
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind

/**
 * Small UI-facing adapter for the TMDB discovery surface.
 *
 * LauncherApp keeps owning the normal browse state. This loader is only used for user-enabled
 * optional rows and for settled-focus Hero enrichment, so those operations remain lazy and cached
 * inside TmdbSearchRepository instead of being duplicated in the composables.
 */
class TmdbDiscoveryLoader(
    private val repository: TmdbSearchRepository,
) {
    suspend fun browse(type: MediaType, rowKeys: List<String>): List<SearchBrowseSection> =
        repository.browse(type, rowKeys).map { section ->
            SearchBrowseSection(
                key = section.key,
                title = section.title,
                items = section.items.map(::toSearchItem),
            )
        }

    suspend fun loadDetails(item: MediaItem): MediaItem = repository.loadDetails(item) ?: item

    private fun toSearchItem(media: MediaItem): SearchItem = SearchItem(
        id = "search:tmdb:${media.type}:${media.tmdbId}",
        kind = SearchResultKind.Tmdb,
        title = media.title,
        subtitle = media.releaseYear?.toString(),
        artworkUri = media.preferredArtworkUri,
        sourceLabel = "TMDB",
        media = media,
    )
}

val LocalTmdbDiscoveryLoader = staticCompositionLocalOf<TmdbDiscoveryLoader?> { null }
