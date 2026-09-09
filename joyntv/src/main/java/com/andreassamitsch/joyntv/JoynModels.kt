package com.andreassamitsch.joyntv

data class JoynProgram(
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val startEpochSeconds: Long? = null,
    val endEpochSeconds: Long? = null,
)

data class JoynLiveChannel(
    val id: String,
    val title: String,
    val type: String,
    val quality: String? = null,
    val markings: Set<String> = emptySet(),
    val logoUrl: String? = null,
    val currentProgram: JoynProgram? = null,
    val nextProgram: JoynProgram? = null,
) {
    val isFree: Boolean
        get() = "PLUS" !in markings && "PREMIUM" !in markings
}

enum class JoynMediaType {
    MOVIE,
    SERIES,
    EPISODE,
    COMPILATION,
    EXTRA,
    SPORT,
    CHANNEL,
    CATEGORY,
    COLLECTION,
    UNKNOWN,
}

data class JoynMediaItem(
    val id: String,
    val title: String,
    val description: String? = null,
    val path: String? = null,
    val type: JoynMediaType = JoynMediaType.UNKNOWN,
    val imageUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val videoId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val licenseTypes: Set<String> = emptySet(),
    val markings: Set<String> = emptySet(),
) {
    val isFree: Boolean
        get() = licenseTypes.none { it == "SVOD" } &&
            "PLUS" !in markings && "PREMIUM" !in markings

    val isDirectlyPlayable: Boolean
        get() = !videoId.isNullOrBlank()
}

data class JoynLane(
    val id: String,
    val title: String,
    val items: List<JoynMediaItem>,
)

data class JoynCataloguePage(
    val title: String,
    val lanes: List<JoynLane>,
)

data class JoynSeriesDetails(
    val series: JoynMediaItem,
    val seasons: List<JoynSeason>,
)

data class JoynSeason(
    val id: String,
    val number: Int,
    val licenseTypes: Set<String> = emptySet(),
)

data class JoynAccountState(
    val loggedIn: Boolean,
    val email: String? = null,
    val hasPlus: Boolean = false,
    val hasHd: Boolean = false,
)

data class JoynPlayback(
    val manifestUrl: String,
    val licenseUrl: String?,
    val certificateUrl: String?,
)

data class JoynRuntimeConfig(
    val country: JoynCountry,
    val apiKey: String,
)

enum class JoynCountry(
    val webSuffix: String,
    val authTenant: String,
    val graphqlTenant: String,
) {
    DE("de", "JOYN_DE", "JOYN"),
    AT("at", "JOYN_AT", "JOYN_AT"),
    CH("ch", "JOYN_CH", "JOYN_CH"),
    ;

    companion object {
        fun fromIsoCountry(value: String?): JoynCountry = JoynRegionSettings.resolveCountry(value)
    }
}
