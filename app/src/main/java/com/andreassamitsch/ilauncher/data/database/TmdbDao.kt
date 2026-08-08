package com.andreassamitsch.ilauncher.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_mappings WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun mapping(sourceKey: String): TmdbMappingEntity?

    @Upsert
    suspend fun upsertMapping(entity: TmdbMappingEntity)

    @Query("SELECT * FROM tmdb_media WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun media(mediaKey: String): TmdbMediaEntity?

    @Upsert
    suspend fun upsertMedia(entity: TmdbMediaEntity)

    @Query("SELECT * FROM tmdb_episodes WHERE episodeKey = :episodeKey LIMIT 1")
    suspend fun episode(episodeKey: String): TmdbEpisodeEntity?

    @Upsert
    suspend fun upsertEpisode(entity: TmdbEpisodeEntity)

    @Query("DELETE FROM tmdb_mappings WHERE updatedAtUtcMillis < :cutoffUtcMillis")
    suspend fun deleteOldMappings(cutoffUtcMillis: Long)

    @Query("DELETE FROM tmdb_media WHERE updatedAtUtcMillis < :cutoffUtcMillis")
    suspend fun deleteOldMedia(cutoffUtcMillis: Long)

    @Query("DELETE FROM tmdb_episodes WHERE updatedAtUtcMillis < :cutoffUtcMillis")
    suspend fun deleteOldEpisodes(cutoffUtcMillis: Long)
}
