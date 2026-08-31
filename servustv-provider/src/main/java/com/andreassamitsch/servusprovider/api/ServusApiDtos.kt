package com.andreassamitsch.servusprovider.api

import com.google.gson.annotations.SerializedName

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
    @SerializedName("media_resources") val mediaResources: List<String> = emptyList(),
    val collections: List<ServusCollectionRefDto> = emptyList(),
)

data class DynamicProductDto(
    val links: List<DynamicLinkDto> = emptyList(),
)

data class DynamicLinkDto(
    val action: String? = null,
    val id: String? = null,
)
