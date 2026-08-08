package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType

data class TmdbMetadata(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val releaseYear: Int?,
    val runtimeMinutes: Int?,
    val posterUri: String?,
    val backdropUri: String?,
    val logoUri: String?,
    val voteAverage: Double?,
    val imdbId: String?,
    val tvdbId: Int?,
    val wikidataId: String?,
    val trailerYoutubeId: String? = null,
    val episode: TmdbEpisodeMetadata? = null,
    val confidence: Float,
)

data class TmdbEpisodeMetadata(
    val tmdbEpisodeId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val overview: String?,
    val airYear: Int?,
    val runtimeMinutes: Int?,
    val stillUri: String?,
    val voteAverage: Double?,
    val trailerYoutubeId: String? = null,
)
