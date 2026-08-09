package com.andreassamitsch.ilauncher.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_channel_mappings")
    suspend fun mappings(): List<EpgChannelMappingEntity>

    @Upsert
    suspend fun upsertMapping(entity: EpgChannelMappingEntity)

    @Upsert
    suspend fun upsertMappings(entities: List<EpgChannelMappingEntity>)

    @Query("DELETE FROM epg_channel_mappings WHERE serviceReference = :serviceReference")
    suspend fun deleteMapping(serviceReference: String)

    @Query("DELETE FROM epg_channel_mappings WHERE matchMethod != 'manual'")
    suspend fun deleteAutomaticMappings()

    @Query("DELETE FROM epg_channel_mappings")
    suspend fun deleteAllMappings()

    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE xmltvChannelId IN (:channelIds) " +
            "AND stopUtcMillis > :windowStartUtcMillis " +
            "AND startUtcMillis < :windowEndUtcMillis " +
            "ORDER BY xmltvChannelId, startUtcMillis",
    )
    suspend fun programs(
        channelIds: List<String>,
        windowStartUtcMillis: Long,
        windowEndUtcMillis: Long,
    ): List<EpgProgramEntity>

    @Upsert
    suspend fun upsertPrograms(entities: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs")
    suspend fun deleteAllPrograms()

    @Query("DELETE FROM epg_programs WHERE stopUtcMillis < :cutoffUtcMillis")
    suspend fun deleteOldPrograms(cutoffUtcMillis: Long)
}
