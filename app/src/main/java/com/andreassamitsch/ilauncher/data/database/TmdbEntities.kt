package com.andreassamitsch.ilauncher.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tmdb_mappings",
    indices = [Index(value = ["tmdbId", "mediaType"])],
)
data class TmdbMappingEntity(
    @PrimaryKey val sourceKey: String,
    val normalizedTitle: String,
    val releaseYear: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val tmdbId: Int,
    val mediaType: String,
    val confidence: Float,
    val updatedAtUtcMillis: Long,
)

@Entity(
    tableName = "tmdb_media",
    indices = [Index(value = ["tmdbId", "mediaType"], unique = true)],
)
data class TmdbMediaEntity(
    @PrimaryKey val mediaKey: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val releaseYear: Int?,
    val runtimeMinutes: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val voteAverage: Double?,
    val imdbId: String?,
    val tvdbId: Int?,
    val wikidataId: String?,
    val updatedAtUtcMillis: Long,
)

@Entity(
    tableName = "tmdb_episodes",
    indices = [Index(value = ["seriesTmdbId", "seasonNumber", "episodeNumber"], unique = true)],
)
data class TmdbEpisodeEntity(
    @PrimaryKey val episodeKey: String,
    val seriesTmdbId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val tmdbEpisodeId: Int,
    val title: String?,
    val overview: String?,
    val airYear: Int?,
    val runtimeMinutes: Int?,
    val stillPath: String?,
    val voteAverage: Double?,
    val updatedAtUtcMillis: Long,
)
