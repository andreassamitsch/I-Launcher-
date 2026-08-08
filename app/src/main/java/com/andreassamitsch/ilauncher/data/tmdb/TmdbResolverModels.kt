package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaType

data class MediaLookup(
    val rawTitle: String,
    val typeHint: MediaType = MediaType.Unknown,
    val releaseYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class ParsedMediaLookup(
    val title: String,
    val normalizedTitle: String,
    val typeHint: MediaType,
    val releaseYear: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

data class TmdbCandidate(
    val id: Int,
    val type: MediaType,
    val title: String,
    val originalTitle: String?,
    val releaseYear: Int?,
    val popularity: Double = 0.0,
)

data class TmdbMatch(
    val candidate: TmdbCandidate,
    val confidence: Float,
)

sealed interface TmdbResolution {
    data class Matched(
        val candidate: TmdbCandidate,
        val confidence: Float,
    ) : TmdbResolution

    data object NoConfidentMatch : TmdbResolution
    data object NotConfigured : TmdbResolution

    data class Failed(
        val reason: String,
    ) : TmdbResolution
}
