package com.andreassamitsch.ilauncher.data.openwebif

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

internal interface OpenWebifApi {
    @GET("api/getservices")
    suspend fun getServices(
        @Query("sRef") serviceReference: String? = null,
        @Query("picon") picon: Int = 0,
    ): OpenWebifServicesResponseDto

    @GET("api/epgnownext")
    suspend fun getNowNext(
        @Query("bRef") bouquetReference: String,
    ): OpenWebifEpgResponseDto
}

internal data class OpenWebifServicesResponseDto(
    val result: Boolean = true,
    val services: List<OpenWebifServiceDto> = emptyList(),
)

internal data class OpenWebifServiceDto(
    @SerializedName("servicename") val serviceName: String? = null,
    @SerializedName("servicereference") val serviceReference: String? = null,
    val picon: String? = null,
    val program: Int? = null,
)

internal data class OpenWebifEpgResponseDto(
    val result: Boolean = true,
    val events: List<OpenWebifEventDto> = emptyList(),
)

internal data class OpenWebifEventDto(
    val id: Long? = null,
    @SerializedName("begin_timestamp") val beginTimestamp: Long? = null,
    @SerializedName("duration_sec") val durationSec: Long? = null,
    val title: String? = null,
    val shortdesc: String? = null,
    val longdesc: String? = null,
    val sref: String? = null,
    val sname: String? = null,
    @SerializedName("now_timestamp") val nowTimestamp: Long? = null,
)
