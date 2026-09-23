package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Old CloudStream versions already wrote the next episode into the resume pointer before checking
 * its stream. These rows predate ILauncherNextEpisodeMonitor and must not bypass its availability
 * check. Only zero-progress TV series resume rows are examined; actively watched content is intact.
 */
internal object ILauncherPendingResumeGuard {
    private const val TAG = "ILauncherPendingResume"
    private const val RECHECK_MS = 30L * 60L * 1000L
    private data class Cached(val allowed: Boolean, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun filter(
        context: Context,
        rows: List<DataStoreHelper.ResumeWatchingResult>,
    ): List<DataStoreHelper.ResumeWatchingResult> {
        if (!isLayout(TV)) return rows
        val active = context.getApiSettings()
        return rows.mapNotNull { card ->
            val parentId = card.parentId
            val season = card.season
            val number = card.episode
            if (parentId == null || season == null || number == null ||
                card.type?.isMovieType() != false || (card.watchPos?.position ?: 0L) > 0L
            ) return@mapNotNull card

            val now = System.currentTimeMillis()
            val key = "${card.apiName}|$parentId|$season|$number"
            cache[key]?.takeIf { it.expires > now }?.let { cached ->
                return@mapNotNull if (cached.allowed) card else null
            }
            // A missing extension, a timeout, or a failed network request is never proof that
            // an announced episode is ready. Do not delete user progress for those failures.
            val api = apis.withLock {
                apis.firstOrNull { it.name == card.apiName && it.name in active }
            } ?: return@mapNotNull null
            val load = runCatching {
                withTimeoutOrNull(10_000) { APIRepository(api).load(card.url) }
            }.getOrNull()
            val series = (load as? Resource.Success<*>)?.value as? TvSeriesLoadResponse
                ?: return@mapNotNull null
            val displaySeasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
            fun coordinates(ep: com.lagradost.cloudstream3.Episode): Pair<Int, Int>? {
                val s = ep.season?.let { displaySeasons[it] ?: it } ?: return null
                val e = ep.episode ?: return null
                return s to e
            }
            val requested = season to number
            val candidate = series.episodes.firstOrNull { coordinates(it) == requested }
                ?: return@mapNotNull null
            if (ILauncherNextEpisodeMonitor.isFuture(candidate.date, now) || candidate.data.isBlank()) {
                // Only an explicitly identified, unplayed, announced successor may be migrated.
                // Never change an in-progress episode or a pointer whose identity has since changed.
                val last = DataStoreHelper.getLastWatched(parentId)
                if (last?.episodeId == card.id && last.season == season && last.episode == number) {
                    val previous = series.episodes.mapNotNull { ep ->
                        val coords = coordinates(ep) ?: return@mapNotNull null
                        if (coords.first < season || coords.first == season && coords.second < number) {
                            coords to ep
                        } else null
                    }.maxWithOrNull(compareBy<Pair<Pair<Int, Int>, com.lagradost.cloudstream3.Episode>> {
                        it.first.first
                    }.thenBy { it.first.second })
                    DataStoreHelper.removeLastWatched(parentId)
                    previous?.let { (coords, ep) ->
                        if (!ILauncherNextEpisodeMonitor.isFuture(ep.date, now) && ep.data.isNotBlank()) {
                            ILauncherNextEpisodeMonitor.recordCompleted(
                                context,
                                buildResultEpisode(
                                    headerName = series.name, name = ep.name,
                                    poster = ep.posterUrl ?: series.posterUrl,
                                    episode = coords.second, seasonIndex = ep.season,
                                    season = coords.first, data = ep.data,
                                    apiName = series.apiName,
                                    id = "${series.apiName}|$parentId|${ep.data}".hashCode(),
                                    index = 0, tvType = series.type, parentId = parentId,
                                ),
                            )
                        }
                    }
                    Log.i(TAG, "migrated announced episode to release monitor parent=$parentId")
                }
                cache[key] = Cached(false, now + RECHECK_MS)
                return@mapNotNull null
            }

            val prepared = buildResultEpisode(
                headerName = series.name, name = candidate.name,
                poster = candidate.posterUrl ?: series.posterUrl,
                episode = number, seasonIndex = candidate.season, season = season,
                data = candidate.data, apiName = series.apiName,
                id = card.id ?: "$parentId|$season|$number".hashCode(),
                index = 0, tvType = series.type, parentId = parentId,
            )
            val generator = RepoLinkGenerator(listOf(prepared), series)
            var hasLink = false
            val completed = runCatching {
                withTimeoutOrNull(20_000) {
                    generator.generateLinks(
                        clearCache = false, sourceTypes = LOADTYPE_INAPP,
                        callback = { (link, uri) -> if (link != null || uri != null) hasLink = true },
                        subtitleCallback = {}, offset = 0, isCasting = false,
                    )
                }
            }.getOrNull()
            val allowed = completed == true && hasLink
            cache[key] = Cached(allowed, now + RECHECK_MS)
            if (allowed) card else null
        }
    }
}
