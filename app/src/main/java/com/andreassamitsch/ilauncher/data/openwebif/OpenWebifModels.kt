package com.andreassamitsch.ilauncher.data.openwebif

import com.andreassamitsch.ilauncher.model.LiveTvChannel

data class OpenWebifBouquet(
    val name: String,
    val serviceReference: String,
)

data class OpenWebifConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val selectedBouquetRef: String? = null,
)

data class OpenWebifState(
    val configured: Boolean = false,
    val receiverLabel: String? = null,
    val baseUrl: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val bouquets: List<OpenWebifBouquet> = emptyList(),
    val selectedBouquetRef: String? = null,
    val channels: List<LiveTvChannel> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastUpdatedUtcMillis: Long? = null,
    val errorMessage: String? = null,
)

internal data class OpenWebifCachedSnapshot(
    val baseUrl: String,
    val bouquets: List<OpenWebifBouquet>,
    val selectedBouquetRef: String?,
    val channels: List<LiveTvChannel>,
    val updatedAtUtcMillis: Long,
)
