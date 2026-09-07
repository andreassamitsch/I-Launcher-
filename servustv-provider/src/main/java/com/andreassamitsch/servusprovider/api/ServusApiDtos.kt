package com.andreassamitsch.servusprovider.api

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

data class SessionDto(
    val token: String?,
    @SerializedName("country_code") val countryCode: String?,
)

data class SearchResponseDto(
    val id: String? = null,
    val label: String? = null,
    @SerializedName("list_type") val listType: String? = null,
    val type: String? = null,
    val cards: List<ServusCardDto> = emptyList(),
    val collections: List<ServusCollectionRefDto> = emptyList(),
    val meta: ServusMetaDto? = null,
)

data class ServusMetaDto(
    val next: String? = null,
    val total: Int? = null,
)

data class ServusCollectionRefDto(
    val id: String? = null,
    @SerializedName("list_type") val listType: String? = null,
    val label: String? = null,
    @SerializedName("device_categories") val deviceCategories: List<String> = emptyList(),
)

data class ServusMediaResourceDto(
    val url: String? = null,
    val orientation: String? = null,
)

data class ServusCardDto(
    val id: String? = null,
    val type: String? = null,
    @SerializedName("content_type") val contentType: String? = null,
    val title: String? = null,
    @SerializedName("show_name") val showName: String? = null,
    val subheading: String? = null,
    val label: String? = null,
    @SerializedName("short_description") val shortDescription: String? = null,
    @SerializedName("long_description") val longDescription: String? = null,
    val duration: Long? = null,
    @SerializedName("formatted_duration") val formattedDuration: String? = null,
    val playable: Boolean? = null,
    @SerializedName("sunrise_timestamp") val sunriseTimestamp: String? = null,
    @SerializedName("sunset_timestamp") val sunsetTimestamp: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("season_number") val seasonNumber: Int? = null,
    @SerializedName("episode_number") val episodeNumber: Int? = null,
    val season: String? = null,
    val chapter: String? = null,
    @SerializedName("deeplink_playlist") val deeplinkPlaylist: String? = null,
    @SerializedName("next_playlist") val nextPlaylist: String? = null,
    @SerializedName("share_url") val shareUrl: String? = null,
    @SerializedName("detail_page_id") val detailPageId: String? = null,
    val tags: List<String> = emptyList(),
    val vertical: List<String> = emptyList(),
    @SerializedName("media_resources")
    @JsonAdapter(MediaResourcesDeserializer::class)
    val mediaResources: Map<String, ServusMediaResourceDto> = emptyMap(),
    val collections: List<ServusCollectionRefDto> = emptyList(),
)

/**
 * Keeps the ServusTV media resource map intact instead of throwing away the API-provided URLs.
 *
 * The normal v5.3 product/collection shape is an object keyed by resource type, e.g.
 * `rbtv_title_treatment_landscape -> { url, orientation }`. A few legacy surfaces can still return
 * strings/arrays; those are retained as keys with an empty metadata object so callers can remain
 * forward/backward compatible without inventing URLs.
 */
class MediaResourcesDeserializer : JsonDeserializer<Map<String, ServusMediaResourceDto>> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): Map<String, ServusMediaResourceDto> {
        if (json == null || json.isJsonNull) return emptyMap()

        if (json.isJsonObject) {
            return buildMap {
                json.asJsonObject.entrySet().forEach { (name, value) ->
                    if (name.isBlank()) return@forEach
                    val metadata = when {
                        value.isJsonObject -> ServusMediaResourceDto(
                            url = value.asJsonObject.get("url")
                                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                ?.asString,
                            orientation = value.asJsonObject.get("orientation")
                                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                ?.asString,
                        )
                        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                            val raw = value.asString
                            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                                ServusMediaResourceDto(url = raw)
                            } else {
                                ServusMediaResourceDto()
                            }
                        }
                        else -> ServusMediaResourceDto()
                    }
                    put(name, metadata)
                }
            }
        }

        val names = buildList {
            fun collect(element: JsonElement?) {
                if (element == null || element.isJsonNull) return
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> add(element.asString)
                    element.isJsonArray -> element.asJsonArray.forEach(::collect)
                }
            }
            collect(json)
        }
        return names.filter { it.isNotBlank() }.distinct().associateWith { ServusMediaResourceDto() }
    }
}

data class DynamicProductDto(
    val id: String? = null,
    val links: List<DynamicLinkDto> = emptyList(),
    val playable: Boolean? = null,
    @SerializedName("header_badges") val headerBadges: List<ServusBadgeDto> = emptyList(),
)

data class DynamicLinkDto(
    val action: String? = null,
    val id: String? = null,
    val label: String? = null,
    val type: String? = null,
)

data class ServusBadgeDto(
    val value: String? = null,
    val type: String? = null,
    val display: String? = null,
)
