package com.andreassamitsch.ilauncher.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_channel_mappings",
    indices = [Index(value = ["xmltvChannelId"])],
)
data class EpgChannelMappingEntity(
    @PrimaryKey val serviceReference: String,
    val xmltvChannelId: String,
    val matchMethod: String,
    val confidence: Float,
    val updatedAtUtcMillis: Long,
)

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["xmltvChannelId", "startUtcMillis"]),
        Index(value = ["startUtcMillis", "stopUtcMillis"]),
    ],
)
data class EpgProgramEntity(
    @PrimaryKey val programKey: String,
    val xmltvChannelId: String,
    val startUtcMillis: Long,
    val stopUtcMillis: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val categories: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val releaseYear: Int?,
    val imageUri: String?,
    val updatedAtUtcMillis: Long,
)
