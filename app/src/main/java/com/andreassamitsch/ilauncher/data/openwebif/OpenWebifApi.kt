package com.andreassamitsch.ilauncher.data.openwebif

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
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

    /**
     * Current Enigma2 frontend/tuner measurements using OpenWebif's JSON API.
     */
    @GET("api/signal")
    suspend fun getSignal(): OpenWebifSignalResponseDto

    /**
     * Legacy XML signal endpoint. Older/vendor OpenWebif builds can expose this even when
     * /api/signal is missing or behaves differently.
     */
    @GET("web/signal")
    suspend fun getLegacySignal(): ResponseBody

    /**
     * Keep Enigma2's foreground service aligned with the network stream.
     *
     * OpenWebif deliberately does not create a foreground service for stream.m3u while the box is
     * in standby. Its signal endpoint, however, reads session.nav.getCurrentService(). Calling the
     * normal zap endpoint for the same service after resolving the stream gives the signal endpoint
     * a frontend to inspect. OpenWebif keeps the receiver in standby; this does not wake HDMI/CEC.
     */
    @GET("api/zap")
    suspend fun zap(
        @Query("sRef") serviceReference: String,
        @Query("title") title: String = "",
    ): OpenWebifActionResponseDto
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
    val pos: Int? = null,
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

internal data class OpenWebifSignalResponseDto(
    val tunertype: String? = null,
    val tunernumber: String? = null,
    val snr: String? = null,
    @SerializedName("snr_db") val snrDb: String? = null,
    val agc: String? = null,
    val ber: String? = null,
)

internal data class OpenWebifActionResponseDto(
    val result: Boolean = true,
    val message: String? = null,
)
