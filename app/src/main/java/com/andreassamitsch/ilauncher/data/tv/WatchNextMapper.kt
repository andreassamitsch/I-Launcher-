package com.andreassamitsch.ilauncher.data.tv

import com.andreassamitsch.ilauncher.model.WatchNextItem

internal data class WatchNextRawRow(
    val id: Long,
    val packageName: String?,
    val programType: Int?,
    val title: String?,
    val seasonDisplayNumber: String?,
    val episodeDisplayNumber: String?,
    val episodeTitle: String?,
    val shortDescription: String?,
    val posterArtUri: String?,
    val thumbnailUri: String?,
    val logoUri: String?,
    val intentUri: String?,
    val durationMillis: Long?,
    val playbackPositionMillis: Long?,
    val watchNextType: Int?,
    val lastEngagementTimeUtcMillis: Long?,
)

internal object WatchNextMapper {
    fun map(rows: List<WatchNextRawRow>): List<WatchNextItem> =
        rows.mapIndexed { index, row ->
            WatchNextItem(
                id = row.id,
                sourceOrder = index,
                packageName = row.packageName,
                programType = row.programType,
                title = row.title,
                seasonDisplayNumber = row.seasonDisplayNumber,
                episodeDisplayNumber = row.episodeDisplayNumber,
                episodeTitle = row.episodeTitle,
                shortDescription = row.shortDescription,
                posterArtUri = row.posterArtUri,
                thumbnailUri = row.thumbnailUri,
                logoUri = row.logoUri,
                intentUri = row.intentUri,
                durationMillis = row.durationMillis,
                playbackPositionMillis = row.playbackPositionMillis,
                watchNextType = row.watchNextType,
                lastEngagementTimeUtcMillis = row.lastEngagementTimeUtcMillis,
            )
        }
}
