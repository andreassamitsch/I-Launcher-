package com.andreassamitsch.joyntv

internal object JoynExactEpisodeMetadata {
    fun matches(entry: JoynContinueWatchingEntry, candidate: JoynMediaItem): Boolean {
        if (candidate.type != JoynMediaType.EPISODE) return false
        val media = entry.media
        if (!media.videoId.isNullOrBlank() && !candidate.videoId.isNullOrBlank()) {
            return media.videoId == candidate.videoId
        }
        if (media.id == candidate.id) return true
        return entry.assetId == candidate.id || entry.assetId == candidate.videoId
    }

    fun enrich(
        entry: JoynContinueWatchingEntry,
        episode: JoynMediaItem?,
        series: JoynMediaItem?,
        seasonArtworkUrl: String?,
    ): JoynContinueWatchingEntry {
        val trustedEpisode = episode?.takeIf { matches(entry, it) }
        val media = entry.media
        if (trustedEpisode == null && series == null) return entry
        return entry.copy(media = media.copy(
            title = trustedEpisode?.title?.takeIf(String::isNotBlank) ?: media.title,
            description = trustedEpisode?.description?.takeIf(String::isNotBlank)
                ?: media.description?.takeIf { it.isNotBlank() && it != series?.description },
            imageUrl = trustedEpisode?.imageUrl ?: media.imageUrl,
            backdropUrl = trustedEpisode?.backdropUrl ?: media.backdropUrl,
            videoId = trustedEpisode?.videoId ?: media.videoId,
            seasonId = trustedEpisode?.seasonId ?: media.seasonId,
            seasonNumber = trustedEpisode?.seasonNumber ?: media.seasonNumber,
            episodeNumber = trustedEpisode?.episodeNumber ?: media.episodeNumber,
            seriesTitle = trustedEpisode?.seriesTitle ?: media.seriesTitle ?: series?.title,
            seriesId = trustedEpisode?.seriesId ?: media.seriesId ?: series?.id,
            seriesPath = trustedEpisode?.seriesPath ?: media.seriesPath ?: series?.path,
            seasonArtworkUrl = seasonArtworkUrl ?: media.seasonArtworkUrl,
            seriesBackdropUrl = series?.backdropUrl ?: series?.imageUrl ?: media.seriesBackdropUrl,
            logoUrl = series?.logoUrl ?: media.logoUrl,
        ))
    }
}
