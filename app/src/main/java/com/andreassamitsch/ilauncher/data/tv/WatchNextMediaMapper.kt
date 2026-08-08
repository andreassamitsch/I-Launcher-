package com.andreassamitsch.ilauncher.data.tv

import android.media.tv.TvContract
import com.andreassamitsch.ilauncher.data.tmdb.TmdbMetadata
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem

object WatchNextMediaMapper {
    fun base(item: WatchNextItem): MediaItem {
        val season = item.seasonDisplayNumber?.toIntOrNull()
        val episode = item.episodeDisplayNumber?.toIntOrNull()
        val type = if (season != null || episode != null) {
            MediaType.Episode
        } else {
            item.programType.toMediaType()
        }

        return MediaItem(
            id = "watch-next:${item.packageName ?: "unknown"}:${item.id}",
            type = type,
            title = item.displayTitle,
            subtitle = item.displaySubtitle,
            overview = item.shortDescription,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = item.episodeTitle,
            logoUri = item.logoUri,
            sourceArtworkUri = item.artworkUri,
            durationMillis = item.durationMillis,
            playbackPositionMillis = item.playbackPositionMillis,
            lastEngagementTimeUtcMillis = item.lastEngagementTimeUtcMillis,
            source = MediaSource(
                provider = "android_watch_next",
                sourceId = "${item.packageName ?: "unknown"}:${item.id}",
                packageName = item.packageName,
                intentUri = item.intentUri,
            ),
        )
    }

    fun enrich(base: MediaItem, metadata: TmdbMetadata): MediaItem {
        val episode = metadata.episode
        val resultingType = if (base.type == MediaType.Episode && episode != null) {
            MediaType.Episode
        } else {
            metadata.mediaType
        }
        val subtitle = if (resultingType == MediaType.Episode) {
            buildList {
                (episode?.seasonNumber ?: base.seasonNumber)?.let { add("S$it") }
                (episode?.episodeNumber ?: base.episodeNumber)?.let { add("E$it") }
                (episode?.title ?: base.episodeTitle)?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ").ifBlank { base.subtitle.orEmpty() }.ifBlank { null }
        } else {
            base.subtitle
        }
        val metadataDuration = when {
            episode?.runtimeMinutes != null -> episode.runtimeMinutes * 60_000L
            metadata.runtimeMinutes != null -> metadata.runtimeMinutes * 60_000L
            else -> null
        }

        return base.copy(
            type = resultingType,
            title = metadata.title.ifBlank { base.title },
            originalTitle = metadata.originalTitle,
            subtitle = subtitle,
            overview = episode?.overview?.takeIf { it.isNotBlank() }
                ?: metadata.overview?.takeIf { it.isNotBlank() }
                ?: base.overview,
            releaseYear = episode?.airYear ?: metadata.releaseYear,
            tmdbId = metadata.tmdbId,
            tmdbEpisodeId = episode?.tmdbEpisodeId,
            seasonNumber = episode?.seasonNumber ?: base.seasonNumber,
            episodeNumber = episode?.episodeNumber ?: base.episodeNumber,
            episodeTitle = episode?.title ?: base.episodeTitle,
            posterUri = metadata.posterUri,
            backdropUri = metadata.backdropUri,
            logoUri = metadata.logoUri ?: base.logoUri,
            episodeStillUri = episode?.stillUri,
            durationMillis = base.durationMillis ?: metadataDuration,
            voteAverage = episode?.voteAverage ?: metadata.voteAverage,
            imdbId = metadata.imdbId,
            tvdbId = metadata.tvdbId,
            wikidataId = metadata.wikidataId,
            resolverConfidence = metadata.confidence,
        )
    }

    private fun Int?.toMediaType(): MediaType = when (this) {
        TvContract.PreviewPrograms.TYPE_MOVIE -> MediaType.Movie
        TvContract.PreviewPrograms.TYPE_TV_SERIES,
        TvContract.PreviewPrograms.TYPE_TV_SEASON,
        -> MediaType.Series
        TvContract.PreviewPrograms.TYPE_TV_EPISODE -> MediaType.Episode
        else -> MediaType.Unknown
    }
}
