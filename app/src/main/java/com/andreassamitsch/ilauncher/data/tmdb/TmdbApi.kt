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

    @GET("3/trending/movie/{timeWindow}")
    suspend fun trendingMovies(
        @Path("timeWindow") timeWindow: String = "week",
        @Query("language") language: String,
    ): TmdbSearchResponseDto

    @GET("3/trending/tv/{timeWindow}")
    suspend fun trendingTv(
        @Path("timeWindow") timeWindow: String = "week",
        @Query("language") language: String,
    ): TmdbSearchResponseDto

    @GET("3/movie/now_playing")
    suspend fun nowPlayingMovies(
        @Query("language") language: String,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/movie/upcoming")
    suspend fun upcomingMovies(
        @Query("language") language: String,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/tv/airing_today")
    suspend fun airingTodayTv(
        @Query("language") language: String,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/tv/on_the_air")
    suspend fun onTheAirTv(
        @Query("language") language: String,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/discover/movie")
    suspend fun discoverMovies(
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("include_video") includeVideo: Boolean = false,
        @Query("sort_by") sortBy: String = "vote_average.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_count.gte") voteCountGte: Int = 300,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/discover/tv")
    suspend fun discoverTv(
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("sort_by") sortBy: String = "vote_average.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("vote_count.gte") voteCountGte: Int = 200,
        @Query("page") page: Int = 1,
    ): TmdbSearchResponseDto

    @GET("3/movie/{movieId}")
    suspend fun movieDetails(
        @Path("movieId") movieId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "images,external_ids,videos",
        @Query("include_image_language") includeImageLanguage: String = "de,en,null",
        @Query("include_video_language") includeVideoLanguage: String = "de,en,null",
    ): TmdbMediaDetailsDto

    @GET("3/tv/{seriesId}")
    suspend fun tvDetails(
        @Path("seriesId") seriesId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "images,external_ids,videos",
        @Query("include_image_language") includeImageLanguage: String = "de,en,null",
        @Query("include_video_language") includeVideoLanguage: String = "de,en,null",
    ): TmdbMediaDetailsDto

    @GET("3/movie/{movieId}")
    suspend fun movieRelations(
        @Path("movieId") movieId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "similar",
    ): TmdbMediaRelationsDto

    @GET("3/tv/{seriesId}")
    suspend fun tvRelations(
        @Path("seriesId") seriesId: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "similar",
    ): TmdbMediaRelationsDto

    @GET("3/collection/{collectionId}")
    suspend fun collectionDetails(
        @Path("collectionId") collectionId: Int,
        @Query("language") language: String,
    ): TmdbCollectionDetailsDto

    @GET("3/movie/{movieId}/credits")
    suspend fun movieCredits(
        @Path("movieId") movieId: Int,
        @Query("language") language: String,
    ): TmdbCreditsDto

    @GET("3/tv/{seriesId}/aggregate_credits")
    suspend fun tvAggregateCredits(
        @Path("seriesId") seriesId: Int,
        @Query("language") language: String,
    ): TmdbCreditsDto

    @GET("3/person/{personId}")
    suspend fun personDetails(
        @Path("personId") personId: Int,
        @Query("language") language: String,
    ): TmdbPersonDetailsDto

    @GET("3/person/{personId}/combined_credits")
    suspend fun personCombinedCredits(
        @Path("personId") personId: Int,
        @Query("language") language: String,
    ): TmdbCombinedCreditsDto

    @GET("3/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}")
    suspend fun episodeDetails(
        @Path("seriesId") seriesId: Int,
        @Path("seasonNumber") seasonNumber: Int,
        @Path("episodeNumber") episodeNumber: Int,
        @Query("language") language: String,
        @Query("append_to_response") appendToResponse: String = "videos",
        @Query("include_video_language") includeVideoLanguage: String = "de,en,null",
    ): TmdbEpisodeDetailsDto
}
