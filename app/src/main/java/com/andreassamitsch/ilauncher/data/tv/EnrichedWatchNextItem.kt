package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.WatchNextItem

data class EnrichedWatchNextItem(
    val sourceItem: WatchNextItem,
    val media: MediaItem,
)
