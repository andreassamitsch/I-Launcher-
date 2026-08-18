package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.net.URI

/**
 * Stable Android-TV Watch Next handoff for the bridge build.
 *
 * CloudStream's upstream continue-watching URI only contains the current episode id. If Android's
 * TvProvider row is newer than CloudStream's local resume pointer, that id can no longer be found
 * and MainActivity silently keeps showing the previous player. The bridge URI therefore carries
 * the stable series parent id plus the exact episode identity shown by Android TV.
 */
internal object ILauncherWatchNextIntent {
    private const val SCHEME = "cloudstreamcontinuewatching"

    data class Target(
        val seriesKey: Int,
        val episodeId: Int?,
        val season: Int?,
        val episode: Int?,
    )

    fun build(
        parentId: Int?,
        episodeId: Int?,
        season: Int?,
        episode: Int?,
        fallbackUrl: String,
    ): String {
        val key = parentId ?: episodeId ?: return fallbackUrl
        return buildString {
            append(SCHEME)
            append("://")
            append(key)
            val query = buildList {
                episodeId?.let { add("episodeId=$it") }
                season?.let { add("season=$it") }
                episode?.let { add("episode=$it") }
            }
            if (query.isNotEmpty()) {
                append('?')
                append(query.joinToString("&"))
            }
        }
    }

    fun parse(rawUri: String): Target? {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME) return null
        val key = (uri.rawAuthority ?: uri.host)?.toIntOrNull()
            ?: rawUri.substringAfter("$SCHEME://", "").substringBefore('?').toIntOrNull()
            ?: return null
        val query = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .filter(String::isNotBlank)
            .map { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }
            .associate { it }
        return Target(
            seriesKey = key,
            episodeId = query["episodeId"]?.toIntOrNull(),
            season = query["season"]?.toIntOrNull(),
            episode = query["episode"]?.toIntOrNull(),
        )
    }

    fun resolve(
        cards: List<DataStoreHelper.ResumeWatchingResult>,
        target: Target,
    ): DataStoreHelper.ResumeWatchingResult? {
        // New bridge URIs use parentId. The id fallback keeps already-published upstream rows valid.
        val base = cards.firstOrNull { it.parentId == target.seriesKey }
            ?: cards.firstOrNull { it.id == target.seriesKey }
            ?: return null
        val exactEpisodeId = target.episodeId ?: base.id
        return base.copy(
            id = exactEpisodeId,
            season = target.season ?: base.season,
            episode = target.episode ?: base.episode,
            watchPos = exactEpisodeId?.let(DataStoreHelper::getViewPos) ?: base.watchPos,
        )
    }
}
