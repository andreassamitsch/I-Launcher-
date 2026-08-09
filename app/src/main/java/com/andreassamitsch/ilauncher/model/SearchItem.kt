package com.andreassamitsch.ilauncher.model

enum class SearchResultKind {
    App,
    WatchNext,
    PreviewProgram,
    EpgProgram,
    Tmdb,
}

data class SearchItem(
    val id: String,
    val kind: SearchResultKind,
    val title: String,
    val subtitle: String? = null,
    val artworkUri: String? = null,
    val sourceLabel: String? = null,
    val media: MediaItem? = null,
    val packageName: String? = null,
    val previewChannelId: String? = null,
    val serviceReference: String? = null,
    val programStartUtcMillis: Long? = null,
)
