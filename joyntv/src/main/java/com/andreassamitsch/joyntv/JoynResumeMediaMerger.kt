package com.andreassamitsch.joyntv

/** Preserve locally known episode metadata when account resume refresh omits it.
 * Only merge items of the same identity; never transfer a different episode's coordinates/artwork. */
internal object JoynResumeMediaMerger {
    fun merge(incoming: JoynMediaItem, previous: JoynMediaItem?): JoynMediaItem {
        if (previous == null || incoming.type != previous.type) return incoming
        val sameVideo = !incoming.videoId.isNullOrBlank() && incoming.videoId == previous.videoId
        if (!sameVideo && incoming.id != previous.id) return incoming
        if (!incoming.videoId.isNullOrBlank() && !previous.videoId.isNullOrBlank() && !sameVideo) {
            return incoming
        }
        val recoveredEpisode = incoming.type == JoynMediaType.EPISODE &&
            (incoming.seasonNumber == null || incoming.episodeNumber == null) &&
            previous.seasonNumber != null && previous.episodeNumber != null
        return incoming.copy(
            videoId = incoming.videoId ?: previous.videoId,
            seasonId = incoming.seasonId ?: previous.seasonId,
            seriesTitle = incoming.seriesTitle ?: previous.seriesTitle,
            seasonNumber = incoming.seasonNumber ?: previous.seasonNumber,
            episodeNumber = incoming.episodeNumber ?: previous.episodeNumber,
            seriesId = incoming.seriesId ?: previous.seriesId,
            seriesPath = incoming.seriesPath ?: previous.seriesPath,
            description = incoming.description ?: previous.description,
            imageUrl = if (recoveredEpisode) previous.imageUrl ?: incoming.imageUrl
                       else incoming.imageUrl ?: previous.imageUrl,
            backdropUrl = if (recoveredEpisode) previous.backdropUrl ?: incoming.backdropUrl
                          else incoming.backdropUrl ?: previous.backdropUrl,
            logoUrl = incoming.logoUrl ?: previous.logoUrl,
            seriesBackdropUrl = incoming.seriesBackdropUrl ?: previous.seriesBackdropUrl,
            seasonArtworkUrl = incoming.seasonArtworkUrl ?: previous.seasonArtworkUrl,
        )
    }
}
