package com.andreassamitsch.ilauncher.data.tv

import android.media.tv.TvContract
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.R
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
        if (channel.type != TvContract.Channels.TYPE_PREVIEW) {
            return@mapNotNull null
        }

        // Channel.COLUMN_BROWSABLE describes whether Android's system Home currently
        // includes this channel. I Launcher is itself a launcher and therefore keeps
        // its own channel visibility preference. Program-level browsable/searchable
        // flags remain authoritative for whether individual cards may be exposed.
        val programs = channel.programs.mapNotNull { program ->
            if (program.browsable != 1 || program.searchable != 1) {
                null
            } else {
                AppContentProgram(
                    sourceOrder = program.sourceOrder,
                    media = program.toMediaItem(channel.id, channel.packageName),
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

    private fun PreviewProgramRawRow.toMediaItem(
        channelId: Long,
        channelPackageName: String?,
    ): MediaItem {
        val effectivePackageName = packageName?.takeIf { it.isNotBlank() }
            ?: channelPackageName?.takeIf { it.isNotBlank() }
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
            id = "preview:${effectivePackageName ?: "unknown"}:$channelId:$id",
            type = type,
            title = displayTitle,
            subtitle = subtitle,
            overview = shortDescription,
            releaseYear = releaseDate?.take(4)?.toIntOrNull(),
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
            logoUri = localizePreviewLogoUri(effectivePackageName, logoUri),
            sourceArtworkUri = thumbnailUri?.takeIf { it.isNotBlank() }
                ?: posterArtUri?.takeIf { it.isNotBlank() },
            durationMillis = durationMillis,
            source = MediaSource(
                provider = "android_preview_channel",
                sourceId = "${effectivePackageName ?: "unknown"}:$channelId:$id",
                packageName = effectivePackageName,
                intentUri = intentUri,
            ),
        )
    }

    /**
     * The ServusTV companion APK owns the canonical 90-second logo and publishes a content URI.
     * Real-TV testing showed that relying on that cross-process image fetch is not stable enough
     * for launcher chrome. I Launcher therefore mirrors this one verified companion asset locally.
     * Coil 3 supports numeric Android resource URIs, so the returned URI remains a normal model for
     * all existing AsyncImage call sites without introducing provider-specific UI branches.
     */
    internal fun localizePreviewLogoUri(packageName: String?, logoUri: String?): String? {
        if (packageName != SERVUS_PROVIDER_PACKAGE || logoUri !in SERVUS_90_LOGO_URIS) return logoUri
        return "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.servus_news_90_logo}"
    }

    private fun Int?.toMediaType(): MediaType = when (this) {
        TvContract.PreviewPrograms.TYPE_MOVIE -> MediaType.Movie
        TvContract.PreviewPrograms.TYPE_TV_SERIES,
        TvContract.PreviewPrograms.TYPE_TV_SEASON,
        -> MediaType.Series
        TvContract.PreviewPrograms.TYPE_TV_EPISODE -> MediaType.Episode
        else -> MediaType.Unknown
    }

    private const val SERVUS_PROVIDER_PACKAGE = "com.andreassamitsch.servusprovider"
    private const val SERVUS_90_CONTENT_LOGO_URI =
        "content://com.andreassamitsch.servusprovider.branding/servus_news_90_logo.png"
    private const val SERVUS_90_LEGACY_LOGO_URI =
        "android.resource://com.andreassamitsch.servusprovider/drawable/servus_news_90_logo"
    private val SERVUS_90_LOGO_URIS = setOf(
        SERVUS_90_CONTENT_LOGO_URI,
        SERVUS_90_LEGACY_LOGO_URI,
    )
}
