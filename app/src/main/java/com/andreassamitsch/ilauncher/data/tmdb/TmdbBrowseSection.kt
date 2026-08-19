package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.model.MediaItem

data class TmdbBrowseSection(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
)
