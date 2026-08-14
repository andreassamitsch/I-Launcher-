package com.andreassamitsch.ilauncher.model

data class MediaCollection(
    val title: String,
    val items: List<MediaItem>,
)

data class MediaRelatedContent(
    val similar: List<MediaItem> = emptyList(),
    val collection: MediaCollection? = null,
) {
    companion object {
        val Empty = MediaRelatedContent()
    }
}
