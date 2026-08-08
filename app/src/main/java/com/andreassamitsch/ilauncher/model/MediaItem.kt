package com.andreassamitsch.ilauncher.model

enum class MediaType {
    Movie,
    Series,
    Episode,
    Unknown,
}

data class MediaSource(
    val provider: String,
    val sourceId: String,
    val packageName: String? = null,
    val intentUri: String? = null,
)

data class MediaItem(
    val id: String,
    val type: MediaType,
    val title: String,
    val originalTitle: String? = null,
    val subtitle: String? = null,
    val overview: String? = null,
    val releaseYear: Int? = null,
    val tmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val posterUri: String? = null,
    val backdropUri: String? = null,
    val logoUri: String? = null,
    val episodeStillUri: String? = null,
    val sourceArtworkUri: String? = null,
    val durationMillis: Long? = null,
    val playbackPositionMillis: Long? = null,
    val lastEngagementTimeUtcMillis: Long? = null,
    val source: MediaSource,
    val resolverConfidence: Float? = null,
) {
    val preferredArtworkUri: String?
        get() = when (type) {
            MediaType.Episode -> episodeStillUri
                ?: backdropUri
                ?: posterUri
                ?: sourceArtworkUri

            MediaType.Series,
            MediaType.Movie,
            MediaType.Unknown,
            -> backdropUri
                ?: posterUri
                ?: sourceArtworkUri
        }

    val progressFraction: Float?
        get() {
            val duration = durationMillis ?: return null
            val position = playbackPositionMillis ?: return null
            if (duration <= 0L || position < 0L) return null
            return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
}
