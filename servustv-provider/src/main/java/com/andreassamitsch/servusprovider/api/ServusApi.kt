package com.andreassamitsch.servusprovider.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ServusApi {
    @GET("v3/session")
    suspend fun session(
        @Query("namespace") namespace: String = "stv",
        @Query("category") category: String = "personal_computer",
        @Query("os_family") osFamily: String = "http",
    ): SessionDto

    @GET("search/v5/stv/de/{market}/top_results")
    suspend fun search(
        @Path("market") market: String,
        @Query("q") query: String,
        @Query("offset") offset: Int,
    ): SearchResponseDto

    @GET("products/v5.3/stv/de/{market}/{id}")
    suspend fun product(
        @Path("market") market: String,
        @Path("id") id: String,
    ): ServusCardDto

    @GET("collections/v5.3/stv/de/{market}/{id}")
    suspend fun collection(
        @Path("market") market: String,
        @Path("id") id: String,
        @Query("offset") offset: Int,
    ): SearchResponseDto

    @GET("products/dynamic/v5/stv/de/{market}/{id}")
    suspend fun dynamicProduct(
        @Path("market") market: String,
        @Path("id") id: String,
    ): DynamicProductDto
}
