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
        fun fromIsoCountry(value: String?): JoynCountry = when (value?.uppercase()) {
            "AT" -> AT
            "CH" -> CH
            else -> DE
        }
    }
}
