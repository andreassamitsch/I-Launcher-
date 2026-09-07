package com.andreassamitsch.ilauncher.data.tv

import android.content.Context
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.WatchNextItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SeriesResumePosition(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val playbackPositionMillis: Long? = null,
    val durationMillis: Long? = null,
    val lastEngagementTimeUtcMillis: Long? = null,
) {
    val label: String
        get() = buildString {
            append("S$seasonNumber E$episodeNumber")
            episodeTitle?.takeIf(String::isNotBlank)?.let { append(" · $it") }
        }
}

class SeriesResumeRepository(context: Context) {
    private val watchNextRepository = WatchNextRepository(context.applicationContext)

    fun observe(series: MediaItem): Flow<SeriesResumePosition?> =
        watchNextRepository.observe().map { result ->
            resolveSeriesResume(series, result.items)
        }
}

/**
 * Resolves the current episode from Android Watch Next without depending on a specific source app.
 * The TvProvider/source order is kept intact; the first matching series row wins.
 */
internal fun resolveSeriesResume(
    series: MediaItem,
    items: List<WatchNextItem>,
): SeriesResumePosition? {
    if (series.type != MediaType.Series) return null
    val acceptedTitles = listOfNotNull(series.title, series.originalTitle)
        .map(::normalizeSeriesResumeTitle)
        .filter(String::isNotBlank)
        .toSet()
    if (acceptedTitles.isEmpty()) return null

    return items.asSequence()
        .mapNotNull { item ->
            val season = item.seasonDisplayNumber?.toIntOrNull() ?: return@mapNotNull null
            val episode = item.episodeDisplayNumber?.toIntOrNull() ?: return@mapNotNull null
            val sourceTitle = item.title?.let(::normalizeSeriesResumeTitle).orEmpty()
            if (sourceTitle !in acceptedTitles) return@mapNotNull null
            SeriesResumePosition(
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = item.episodeTitle,
                playbackPositionMillis = item.playbackPositionMillis,
                durationMillis = item.durationMillis,
                lastEngagementTimeUtcMillis = item.lastEngagementTimeUtcMillis,
            )
        }
        .firstOrNull()
}

internal fun seriesPlaybackTarget(
    series: MediaItem,
    resume: SeriesResumePosition?,
): MediaItem {
    require(series.type == MediaType.Series) { "Series playback target requires MediaType.Series" }
    val season = resume?.seasonNumber ?: 1
    val episode = resume?.episodeNumber ?: 1
    return series.copy(
        id = "${series.id}:episode:$season:$episode",
        type = MediaType.Episode,
        subtitle = buildString {
            append("S$season E$episode")
            resume?.episodeTitle?.takeIf(String::isNotBlank)?.let { append(" · $it") }
        },
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = resume?.episodeTitle,
        playbackPositionMillis = resume?.playbackPositionMillis,
        durationMillis = resume?.durationMillis ?: series.durationMillis,
        lastEngagementTimeUtcMillis = resume?.lastEngagementTimeUtcMillis ?: series.lastEngagementTimeUtcMillis,
    )
}

internal fun normalizeSeriesResumeTitle(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
