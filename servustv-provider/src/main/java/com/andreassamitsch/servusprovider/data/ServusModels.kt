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
    /**
     * Parent-show landscape artwork resolved from the already cached show product.
     *
     * This stays separate from [artworkUri], which is the concrete episode landscape. It lets the
     * aggregate `ServusTV Aktuelles` channel publish stable show art for the rail card while still
     * publishing the episode image for the focused Hero, without any extra API request.
     */
    val showArtworkUri: String? = null,
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val contentType: String? = null,
    /** Episode metadata supplied by ServusTV where the format has numbered seasons/episodes. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** The concrete show-page collection that supplied this item. */
    val sourceCollectionId: String? = null,
    val sourceCollectionTitle: String? = null,
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

enum class ServusCollectionRole {
    CONTENT,
    RECOMMENDATION,
    INFO,
    UNKNOWN,
}

/**
 * One editorial rail below a ServusTV show page.
 *
 * Collections remain first-class so users can opt a complete show or a specific editorial rail into
 * `Aktuelles` / Android TV. Recommendation and Playnet/info rails are kept as metadata but never
 * traversed as episode sources.
 */
data class ServusShowCollection(
    val id: String,
    val title: String,
    val listType: String? = null,
    val type: String? = null,
    val role: ServusCollectionRole = ServusCollectionRole.UNKNOWN,
    /** Parent show identity carried by the collection's actual episode products where known. */
    val contentShowId: String? = null,
    val contentShowTitle: String? = null,
    val episodes: List<ServusNewsEpisode> = emptyList(),
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
    val collections: List<ServusShowCollection> = emptyList(),
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
