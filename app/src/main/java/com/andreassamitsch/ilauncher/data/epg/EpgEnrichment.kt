package com.andreassamitsch.ilauncher.data.epg

import com.andreassamitsch.ilauncher.data.tmdb.TmdbMetadata
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaType

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

/** EPG describes this airing; TMDB describes a matched work, not necessarily this episode. */
internal fun LiveTvProgram.withTmdbEnrichment(metadata: TmdbMetadata): LiveTvProgram {
    val episode = metadata.episode?.takeIf {
        it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
    }
    val isMovie = metadata.mediaType == MediaType.Movie
    val specificOverview = episode?.overview.nonBlank()
        ?: metadata.overview.nonBlank()?.takeIf { isMovie }
    return copy(
        subtitle = subtitle.nonBlank() ?: episode?.title.nonBlank(),
        longDescription = longDescription.nonBlank() ?: shortDescription.nonBlank() ?: specificOverview,
        shortDescription = shortDescription.nonBlank() ?: longDescription.nonBlank() ?: specificOverview,
        releaseYear = releaseYear ?: episode?.airYear ?: metadata.releaseYear?.takeIf { isMovie },
        tmdbId = metadata.tmdbId,
        tmdbEpisodeId = episode?.tmdbEpisodeId,
        tmdbType = metadata.mediaType,
        tmdbTitle = metadata.title,
        tmdbOverview = specificOverview,
        tmdbReleaseYear = episode?.airYear ?: metadata.releaseYear,
        tmdbLogoUri = metadata.logoUri,
        posterUri = metadata.posterUri,
        backdropUri = metadata.backdropUri,
        episodeStillUri = episode?.stillUri,
        voteAverage = episode?.voteAverage ?: metadata.voteAverage,
    )
}

/** Call only within a single service reference; never transfer metadata to a different airing. */
internal fun LiveTvProgram.retainEpgEnrichment(previous: LiveTvProgram?): LiveTvProgram {
    if (previous?.tmdbId == null || startUtcMillis != previous.startUtcMillis ||
        EpgChannelMatcher.normalizeName(title) != EpgChannelMatcher.normalizeName(previous.title) ||
        seasonNumber != previous.seasonNumber || episodeNumber != previous.episodeNumber ||
        (eventId != null && previous.eventId != null && eventId != previous.eventId) ||
        (xmltvChannelId != null && previous.xmltvChannelId != null && xmltvChannelId != previous.xmltvChannelId)
    ) return this
    return copy(
        subtitle = subtitle.nonBlank() ?: previous.subtitle.nonBlank()?.takeIf { previous.tmdbEpisodeId != null },
        longDescription = longDescription.nonBlank() ?: shortDescription.nonBlank() ?: previous.tmdbOverview,
        shortDescription = shortDescription.nonBlank() ?: longDescription.nonBlank() ?: previous.tmdbOverview,
        releaseYear = releaseYear ?: previous.tmdbReleaseYear?.takeIf {
            previous.tmdbType == MediaType.Movie || previous.tmdbEpisodeId != null
        },
        tmdbId = previous.tmdbId,
        tmdbEpisodeId = previous.tmdbEpisodeId,
        tmdbType = previous.tmdbType,
        tmdbTitle = previous.tmdbTitle,
        tmdbOverview = previous.tmdbOverview,
        tmdbReleaseYear = previous.tmdbReleaseYear,
        tmdbLogoUri = previous.tmdbLogoUri,
        posterUri = previous.posterUri,
        backdropUri = previous.backdropUri,
        episodeStillUri = previous.episodeStillUri,
        voteAverage = previous.voteAverage,
    )
}
