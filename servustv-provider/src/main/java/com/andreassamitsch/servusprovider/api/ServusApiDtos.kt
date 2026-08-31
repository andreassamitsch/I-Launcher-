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
    val cards: List<ServusCardDto> = emptyList(),
    val collections: List<ServusCollectionRefDto> = emptyList(),
    val meta: ServusMetaDto? = null,
)

data class ServusMetaDto(
    val next: String? = null,
)

data class ServusCollectionRefDto(
    val id: String? = null,
    @SerializedName("list_type") val listType: String? = null,
    val label: String? = null,
)

data class ServusCardDto(
    val id: String? = null,
    val type: String? = null,
    @SerializedName("content_type") val contentType: String? = null,
    val title: String? = null,
    @SerializedName("show_name") val showName: String? = null,
    val subheading: String? = null,
    @SerializedName("short_description") val shortDescription: String? = null,
    @SerializedName("long_description") val longDescription: String? = null,
    val duration: Long? = null,
    val playable: Boolean? = null,
    @SerializedName("sunrise_timestamp") val sunriseTimestamp: String? = null,
    @SerializedName("sunset_timestamp") val sunsetTimestamp: String? = null,
    @SerializedName("media_resources")
    @JsonAdapter(MediaResourcesDeserializer::class)
    val mediaResources: List<String> = emptyList(),
    val collections: List<ServusCollectionRefDto> = emptyList(),
)

/**
 * ServusTV currently returns `media_resources` in more than one JSON shape.
 *
 * Some endpoints/cards use an array of resource names while others use an object whose keys
 * are the resource names and whose values contain resource metadata. Normalising both forms at
 * the DTO boundary keeps the repository and artwork policy independent from that API detail.
 */
class MediaResourcesDeserializer : JsonDeserializer<List<String>> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): List<String> = collectResourceNames(json).distinct()

    private fun collectResourceNames(element: JsonElement?): List<String> {
        if (element == null || element.isJsonNull) return emptyList()

        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> listOf(element.asString)
            element.isJsonArray -> element.asJsonArray.flatMap(::collectResourceNames)
            element.isJsonObject -> buildList {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    // For the object form the API uses the media-resource identifier as the key.
                    add(key)
                    // Also inspect nested values so the parser remains compatible with wrapper objects.
                    addAll(collectResourceNames(value))
                }
            }
            else -> emptyList()
        }
    }
}

data class DynamicProductDto(
    val links: List<DynamicLinkDto> = emptyList(),
)

data class DynamicLinkDto(
    val action: String? = null,
    val id: String? = null,
)
