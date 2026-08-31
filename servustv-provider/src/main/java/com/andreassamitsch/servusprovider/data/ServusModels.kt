package com.andreassamitsch.servusprovider.data

data class ServusSession(
    val token: String,
    val countryCode: String,
    val createdAtMillis: Long,
)

data class ServusNewsEpisode(
    val id: String,
    val title: String,
    val showName: String?,
    val description: String?,
    val durationMillis: Long,
    val publishedAtMillis: Long,
    val artworkUri: String?,
)

data class ServusRefreshResult(
    val episodes: List<ServusNewsEpisode>,
    val refreshedAtMillis: Long,
)
