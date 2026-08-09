package com.andreassamitsch.ilauncher.data.tv

import android.media.tv.TvContract
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType

internal data class PreviewChannelRawRow(
    val id: Long,
    val sourceOrder: Int,
    val packageName: String?,
    val displayName: String?,
    val appLinkIntentUri: String?,
    val browsable: Int?,
    val type: String?,
    val programs: List<PreviewProgramRawRow>,
)

internal data class PreviewProgramRawRow(
    val id: Long,
    val sourceOrder: Int,
    val packageName: String?,
    val programType: Int?,
    val title: String?,
    val releaseDate: String?,
    val seasonDisplayNumber: String?,
    val episodeDisplayNumber: String?,
    val episodeTitle: String?,
    val shortDescription: String?,
    val posterArtUri: String?,
    val thumbnailUri: String?,
    val logoUri: String?,
    val intentUri: String?,
    val durationMillis: Long?,
    val weight: Int?,
    val browsable: Int?,
    val searchable: Int?,
)

internal object PreviewChannelsMapper {
    fun map(rows: List<PreviewChannelRawRow>): List<AppContentChannel> = rows.mapNotNull { channel ->
        if (channel.type != TvContract.Channels.TYPE_PREVIEW || channel.browsable == 0) {
            return@mapNotNull null
        }

        val programs = channel.programs.mapNotNull { program ->
            if (program.browsable == 0 || program.searchable == 0) {
                null
            } else {
                AppContentProgram(
                    sourceOrder = program.sourceOrder,
                    media = program.toMediaItem(channel.id),
                    weight = program.weight,
                )
            }
        }

        AppContentChannel(
            id = "android-preview:${channel.packageName ?: "unknown"}:${channel.id}",
            sourceOrder = channel.sourceOrder,
            packageName = channel.packageName,
            title = channel.displayName?.takeIf { it.isNotBlank() }
                ?: channel.packageName?.takeIf { it.isNotBlank() }
                ?: "App-Kanal",
            appLinkIntentUri = channel.appLinkIntentUri,
            programs = programs,
        )
    }

    private fun PreviewProgramRawRow.toMediaItem(channelId: Long): MediaItem {
        val season = seasonDisplayNumber?.toIntOrNull()
        val episode = episodeDisplayNumber?.toIntOrNull()
        val type = if (season != null || episode != null) {
            MediaType.Episode
        } else {
            programType.toMediaType()
        }
        val displayTitle = title?.takeIf { it.isNotBlank() }
            ?: episodeTitle?.takeIf { it.isNotBlank() }
            ?: "Unbenannter Inhalt"
        val episodePrefix = buildList {
            seasonDisplayNumber?.takeIf { it.isNotBlank() }?.let { add("S$it") }
            episodeDisplayNumber?.takeIf { it.isNotBlank() }?.let { add("E$it") }
        }.joinToString(" ")
        val subtitle = listOfNotNull(
            episodePrefix.takeIf { it.isNotBlank() },
            episodeTitle?.takeIf { it.isNotBlank() && it != displayTitle },
        ).joinToString(" · ").ifBlank { null }

        return MediaItem(
            id = "preview:${packageName ?: "unknown"}:$channelId:$id",
            type = type,
            title = displayTitle,
            subtitle = subtitle,
            overview = shortDescription,
            releaseYear = releaseDate?.take(4)?.toIntOrNull(),
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
            logoUri = logoUri,
            sourceArtworkUri = thumbnailUri?.takeIf { it.isNotBlank() }
                ?: posterArtUri?.takeIf { it.isNotBlank() },
            durationMillis = durationMillis,
            source = MediaSource(
                provider = "android_preview_channel",
                sourceId = "${packageName ?: "unknown"}:$channelId:$id",
                packageName = packageName,
                intentUri = intentUri,
            ),
        )
    }

    private fun Int?.toMediaType(): MediaType = when (this) {
        TvContract.PreviewPrograms.TYPE_MOVIE -> MediaType.Movie
        TvContract.PreviewPrograms.TYPE_TV_SERIES,
        TvContract.PreviewPrograms.TYPE_TV_SEASON,
        -> MediaType.Series
        TvContract.PreviewPrograms.TYPE_TV_EPISODE -> MediaType.Episode
        else -> MediaType.Unknown
    }
}
