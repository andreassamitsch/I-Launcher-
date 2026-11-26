package com.andreassamitsch.joyntv

/**
 * Newest numbered seasons and episodes first for browsing only.
 * Playback / Watch Next continues to use chronological episode selection.
 */
internal object JoynSeriesDisplayOrder {
    fun seasons(items: List<JoynSeason>): List<JoynSeason> =
        items.sortedByDescending { it.number }

    fun episodes(items: List<JoynMediaItem>): List<JoynMediaItem> =
        items.sortedByDescending { it.episodeNumber ?: Int.MIN_VALUE }
}
