package com.andreassamitsch.ilauncher.model

data class SeriesSeason(
    val seasonNumber: Int,
    val title: String,
    val episodeCount: Int,
    val airYear: Int? = null,
    val posterUri: String? = null,
)

data class SeriesSeasonContent(
    val season: SeriesSeason,
    val episodes: List<MediaItem>,
)
