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
    val showId: String? = null,
    val logoUri: String? = null,
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val contentType: String? = null,
)

data class ServusRefreshResult(
    val episodes: List<ServusNewsEpisode>,
    val refreshedAtMillis: Long,
)

data class ServusShow(
    val id: String,
    val title: String,
    val description: String?,
    val categoryId: String,
    val categoryTitle: String,
    val artworkUri: String?,
    val squareArtworkUri: String?,
    val logoUri: String?,
    val episodes: List<ServusNewsEpisode>,
)

data class ServusCategory(
    val id: String,
    val title: String,
    val order: Int,
    val shows: List<ServusShow>,
)

data class ServusLiveProgram(
    val id: String?,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val startAtMillis: Long,
    val endAtMillis: Long,
)

data class ServusLiveChannel(
    val id: String,
    val title: String,
    val description: String?,
    val artworkUri: String?,
    val squareArtworkUri: String?,
    val logoUri: String?,
    val programs: List<ServusLiveProgram>,
) {
    fun currentProgram(nowMillis: Long = System.currentTimeMillis()): ServusLiveProgram? =
        programs.firstOrNull { nowMillis in it.startAtMillis until it.endAtMillis }
            ?: programs.firstOrNull { it.startAtMillis > nowMillis }
}

data class ServusHubSnapshot(
    val categories: List<ServusCategory>,
    val liveChannels: List<ServusLiveChannel>,
    val refreshedAtMillis: Long,
)
