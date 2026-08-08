package com.andreassamitsch.ilauncher.model

data class WatchNextItem(
    val id: Long,
    val sourceOrder: Int,
    val packageName: String?,
    val title: String?,
    val seasonDisplayNumber: String?,
    val episodeDisplayNumber: String?,
    val episodeTitle: String?,
    val shortDescription: String?,
    val posterArtUri: String?,
    val thumbnailUri: String?,
    val logoUri: String?,
    val intentUri: String?,
    val durationMillis: Long?,
    val playbackPositionMillis: Long?,
    val watchNextType: Int?,
    val lastEngagementTimeUtcMillis: Long?,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: episodeTitle?.takeIf { it.isNotBlank() }
            ?: "Unbenannter Inhalt"

    val displaySubtitle: String?
        get() {
            val episodePrefix = buildList {
                seasonDisplayNumber?.takeIf { it.isNotBlank() }?.let { add("S$it") }
                episodeDisplayNumber?.takeIf { it.isNotBlank() }?.let { add("E$it") }
            }.joinToString(separator = " ")

            return listOfNotNull(
                episodePrefix.takeIf { it.isNotBlank() },
                episodeTitle?.takeIf { it.isNotBlank() && it != displayTitle },
            ).joinToString(separator = " · ").ifBlank { null }
        }

    val artworkUri: String?
        get() = thumbnailUri?.takeIf { it.isNotBlank() }
            ?: posterArtUri?.takeIf { it.isNotBlank() }

    val progressFraction: Float?
        get() {
            val duration = durationMillis ?: return null
            val position = playbackPositionMillis ?: return null
            if (duration <= 0L || position < 0L) return null
            return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
}

data class WatchNextLoadResult(
    val items: List<WatchNextItem>,
    val errorMessage: String? = null,
) {
    val isAvailable: Boolean
        get() = errorMessage == null
}
