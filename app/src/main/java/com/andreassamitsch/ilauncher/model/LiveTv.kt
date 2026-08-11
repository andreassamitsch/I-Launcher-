package com.andreassamitsch.ilauncher.model

data class LiveTvProgram(
    val eventId: Long?,
    val title: String,
    val shortDescription: String? = null,
    val longDescription: String? = null,
    val startUtcMillis: Long,
    val durationMillis: Long,
    val subtitle: String? = null,
    val categories: List<String>? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val releaseYear: Int? = null,
    val imageUri: String? = null,
    val xmltvChannelId: String? = null,
    val tmdbId: Int? = null,
    val tmdbEpisodeId: Int? = null,
    val tmdbType: MediaType? = null,
    val tmdbTitle: String? = null,
    val tmdbOverview: String? = null,
    val tmdbReleaseYear: Int? = null,
    val tmdbLogoUri: String? = null,
    val posterUri: String? = null,
    val backdropUri: String? = null,
    val episodeStillUri: String? = null,
    val voteAverage: Double? = null,
) {
    val endUtcMillis: Long
        get() = startUtcMillis + durationMillis

    val preferredArtworkUri: String?
        get() = episodeStillUri
            ?: backdropUri
            ?: imageUri
            ?: posterUri
}

data class LiveTvChannel(
    val serviceReference: String,
    val name: String,
    val piconUri: String? = null,
    val now: LiveTvProgram? = null,
    val next: LiveTvProgram? = null,
) {
    fun progressFraction(nowUtcMillis: Long = System.currentTimeMillis()): Float? {
        val current = now ?: return null
        if (current.durationMillis <= 0L) return null
        return ((nowUtcMillis - current.startUtcMillis).toFloat() / current.durationMillis.toFloat())
            .coerceIn(0f, 1f)
    }
}
