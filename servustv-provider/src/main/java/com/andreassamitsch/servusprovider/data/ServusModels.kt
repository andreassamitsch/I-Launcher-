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
    /**
     * Availability timestamp supplied directly by ServusTV (`sunrise_timestamp`).
     *
     * Broadcast/program times and times parsed from titles must never be stored here: they describe
     * the linear TV slot, not when the VOD actually became available. Null deliberately means that
     * ServusTV did not provide a trustworthy availability timestamp for this item.
     */
    val publishedAtMillis: Long?,
    val artworkUri: String?,
    val showId: String? = null,
    val logoUri: String? = null,
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val contentType: String? = null,
    /**
     * Local first observation of this content ID during a periodic refresh. This is intentionally
     * separate from `publishedAtMillis`: it is an approximation (bounded by the refresh interval),
     * but it tells us when the app actually observed the VOD online without inventing a source time.
     */
    val observedAvailableAtMillis: Long? = null,
    /**
     * Stable editorial format identity captured while the API still exposes enough context to know
     * it reliably (for example the source collection `Servus Nachrichten in 90 Sekunden`).
     *
     * Titles, `show_name` and show caches are mutable and can be incomplete. Persisting this hint
     * prevents a later show refresh from silently turning a 90-second item into generic 19:20 news.
     */
    val contentKindHint: ServusContentKind? = null,
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
