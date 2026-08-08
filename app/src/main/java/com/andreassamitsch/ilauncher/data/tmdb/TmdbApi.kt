package com.andreassamitsch.ilauncher.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

internal interface TmdbApi {
    @GET("3/configuration")
    suspend fun configuration(): TmdbConfigurationDto

    @GET("3/search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("primary_release_year") releaseYear: Int? = null,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/movie/{movieId}")
    suspend fun movieDetails(
        @Path("movieId") movieId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "images,external_ids",
        @Query("include_image_language") includeImageLanguage: String = "de,en,null",
    ): TmdbMediaDetailsDto

    @GET("3/tv/{seriesId}")
    suspend fun tvDetails(
        @Path("seriesId") seriesId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "images,external_ids",
        @Query("include_image_language") includeImageLanguage: String = "de,en,null",
    ): TmdbMediaDetailsDto

    @GET("3/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}")
    suspend fun episodeDetails(
        @Path("seriesId") seriesId: Int,
        @Path("seasonNumber") seasonNumber: Int,
        @Path("episodeNumber") episodeNumber: Int,
        @Query("language") language: String,
    ): TmdbEpisodeDetailsDto
}
