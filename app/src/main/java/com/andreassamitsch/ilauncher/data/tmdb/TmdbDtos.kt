package com.andreassamitsch.ilauncher.data.tmdb

import com.google.gson.annotations.SerializedName

internal data class TmdbSearchResponseDto(
    val results: List<TmdbSearchResultDto> = emptyList(),
)

internal data class TmdbSearchResultDto(
    val id: Int = 0,
    @SerializedName("media_type") val mediaType: String? = null,
    val title: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    val name: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("vote_count") val voteCount: Int = 0,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    val adult: Boolean = false,
)

internal data class TmdbConfigurationDto(
    val images: TmdbImageConfigurationDto = TmdbImageConfigurationDto(),
)

internal data class TmdbImageConfigurationDto(
    @SerializedName("secure_base_url") val secureBaseUrl: String = "https://image.tmdb.org/t/p/",
    @SerializedName("backdrop_sizes") val backdropSizes: List<String> = emptyList(),
    @SerializedName("logo_sizes") val logoSizes: List<String> = emptyList(),
    @SerializedName("poster_sizes") val posterSizes: List<String> = emptyList(),
    @SerializedName("still_sizes") val stillSizes: List<String> = emptyList(),
    @SerializedName("profile_sizes") val profileSizes: List<String> = emptyList(),
)

internal data class TmdbMediaDetailsDto(
    val id: Int = 0,
    val title: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    val name: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    val images: TmdbImagesDto? = null,
    val videos: TmdbVideoResponseDto? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
)

internal data class TmdbMediaRelationsDto(
    @SerializedName("belongs_to_collection") val belongsToCollection: TmdbCollectionRefDto? = null,
    val similar: TmdbSearchResponseDto? = null,
)

internal data class TmdbCollectionRefDto(
    val id: Int = 0,
    val name: String? = null,
)

internal data class TmdbCollectionDetailsDto(
    val id: Int = 0,
    val name: String? = null,
    val parts: List<TmdbSearchResultDto> = emptyList(),
)

internal data class TmdbEpisodeDetailsDto(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("air_date") val airDate: String? = null,
    @SerializedName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    val videos: TmdbVideoResponseDto? = null,
)

internal data class TmdbImagesDto(
    val posters: List<TmdbImageDto> = emptyList(),
    val backdrops: List<TmdbImageDto> = emptyList(),
    val logos: List<TmdbImageDto> = emptyList(),
)

internal data class TmdbImageDto(
    @SerializedName("file_path") val filePath: String? = null,
    @SerializedName("iso_639_1") val language: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
)

internal data class TmdbVideoResponseDto(
    val results: List<TmdbVideoDto> = emptyList(),
)

internal data class TmdbVideoDto(
    val id: String = "",
    val key: String = "",
    val name: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean = false,
    @SerializedName("iso_639_1") val language: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
)

internal data class TmdbExternalIdsDto(
    @SerializedName("imdb_id") val imdbId: String? = null,
    @SerializedName("tvdb_id") val tvdbId: Int? = null,
    @SerializedName("wikidata_id") val wikidataId: String? = null,
)

internal data class TmdbCreditsDto(
    val cast: List<TmdbCreditPersonDto> = emptyList(),
    val crew: List<TmdbCreditPersonDto> = emptyList(),
)

internal data class TmdbCreditPersonDto(
    val id: Int = 0,
    val name: String? = null,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    val order: Int? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
    val roles: List<TmdbCreditRoleDto> = emptyList(),
    val jobs: List<TmdbCreditJobDto> = emptyList(),
)

internal data class TmdbCreditRoleDto(
    val character: String? = null,
    @SerializedName("episode_count") val episodeCount: Int = 0,
)

internal data class TmdbCreditJobDto(
    val job: String? = null,
    @SerializedName("episode_count") val episodeCount: Int = 0,
)

internal data class TmdbPersonDetailsDto(
    val id: Int = 0,
    val name: String? = null,
    val biography: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
    @SerializedName("known_for_department") val knownForDepartment: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerializedName("place_of_birth") val placeOfBirth: String? = null,
)

internal data class TmdbCombinedCreditsDto(
    val cast: List<TmdbPersonCreditDto> = emptyList(),
    val crew: List<TmdbPersonCreditDto> = emptyList(),
)

internal data class TmdbPersonCreditDto(
    val id: Int = 0,
    @SerializedName("media_type") val mediaType: String? = null,
    val title: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    val name: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
    val character: String? = null,
    val job: String? = null,
)
