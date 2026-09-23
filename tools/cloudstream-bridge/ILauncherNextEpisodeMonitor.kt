package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper.getKey
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The catalogue is checked inside CloudStream, never by the Launcher or by guessing a TMDB date. */
internal object ILauncherNextEpisodeMonitor {
    private const val PREFS = "ilauncher_cloudstream_episode_monitor_v1"
    private const val KEY = "tracked"
    private const val TAG = "ILauncherNextEpisode"
    private const val REFRESH_INTERVAL = 6L * 60L * 60L * 1000L
    private const val MAX_AGE = 180L * 24L * 60L * 60L * 1000L
    private val mutex = Mutex()

    internal data class Tracked(
        val parentId: Int,
        val apiName: String,
        val seriesUrl: String,
        val title: String,
        val poster: String?,
        val finishedSeason: Int,
        val finishedEpisode: Int,
        val completedAt: Long,
        val lastCheck: Long = 0L,
        val observedAbsent: Boolean = false,
        val nextSeason: Int? = null,
        val nextEpisode: Int? = null,
        val nextPoster: String? = null,
        val nextDescription: String? = null,
        val newlyAvailable: Boolean = false,
    )

    internal fun normalizedAirDate(value: Long?): Long? = value?.takeIf { it > 0L }?.let {
        if (it < 10_000_000_000L) it * 1000L else it
    }

    internal fun isFuture(value: Long?, now: Long): Boolean =
        normalizedAirDate(value)?.let { it > now } ?: false

    internal fun nextCoordinates(season: Int, episode: Int, candidates: List<Pair<Int, Int>>): Pair<Int, Int>? =
        candidates.asSequence().filter { (s, e) -> s > season || s == season && e > episode }
            .minWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })

    fun recordCompleted(context: Context?, episode: ResultEpisode) {
        val appContext = context?.applicationContext ?: return
        val season = episode.season ?: return
        if (season < 0 || episode.episode < 1) return
        val header = getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, episode.parentId.toString()
        ) ?: return
        if (header.apiName != episode.apiName || header.url.isBlank()) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val previous = read(prefs).firstOrNull { it.parentId == episode.parentId && it.apiName == episode.apiName }
            if (previous != null && (previous.finishedSeason > season ||
                    previous.finishedSeason == season && previous.finishedEpisode >= episode.episode)) return
            val updated = Tracked(
                parentId = episode.parentId, apiName = header.apiName, seriesUrl = header.url,
                title = header.name, poster = header.poster, finishedSeason = season,
                finishedEpisode = episode.episode, completedAt = System.currentTimeMillis()
            )
            write(prefs, read(prefs).filterNot { it.parentId == episode.parentId && it.apiName == episode.apiName } + updated)
        }
    }

    /** No network work in the TvProvider write lock. A later scan fills the saved announcement. */
    fun snapshot(context: Context): List<Pair<String, WatchNextProgram>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).mapNotNull { state ->
            val season = state.nextSeason ?: return@mapNotNull null
            val episode = state.nextEpisode ?: return@mapNotNull null
            // The next episode is already a normal resume card once playback begins.
            if (getLastWatched(state.parentId) != null) return@mapNotNull null
            val id = "ilauncher-next:${state.apiName}:${state.parentId}"
            val intent = Uri.Builder().scheme("cloudstreamplay").authority("v1")
                .appendQueryParameter("title", state.title)
                .appendQueryParameter("type", "episode")
                .appendQueryParameter("season", season.toString())
                .appendQueryParameter("episode", episode.toString())
                .appendQueryParameter("provider", state.apiName)
                .build()
            val program = WatchNextProgram.Builder()
                .setInternalProviderId(id)
                .setTitle(state.title)
                .setEpisodeTitle(state.title)
                .setSeasonNumber(season)
                .setEpisodeNumber(episode)
                .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                .setWatchNextType(if (state.newlyAvailable) {
                    TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
                } else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                .setPosterArtUri((state.nextPoster ?: state.poster)?.let(Uri::parse))
                .setIntentUri(intent)
                .setLastEngagementTimeUtcMillis(state.completedAt)
                .apply { state.nextDescription?.takeIf(String::isNotBlank)?.let { setDescription(it) } }
                .build()
            id to program
        }
    }

    /** Runs only after the installed extensions are active. Failure is not evidence of absence. */
    suspend fun refresh(context: Context) {
        if (!isLayout(TV) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!mutex.tryLock()) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val active = context.getApiSettings()
            var changed = false
            val updated = read(prefs).toMutableList()
            updated.indices.filter { index ->
                val state = updated[index]
                state.apiName in active && state.lastCheck + REFRESH_INTERVAL <= now &&
                    state.nextEpisode == null
            }.take(6).forEach { index ->
                val state = updated[index]
                val api = apis.withLock { apis.firstOrNull { it.name == state.apiName } } ?: return@forEach
                val result = runCatching {
                    withTimeoutOrNull(12_000) { APIRepository(api).load(state.seriesUrl) }
                }.getOrNull()
                val series = (result as? Resource.Success<*>)?.value as? TvSeriesLoadResponse ?: return@forEach
                val seasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
                val candidates = series.episodes.mapNotNull { ep ->
                    val season = ep.season?.let { seasons[it] ?: it } ?: return@mapNotNull null
                    val number = ep.episode ?: return@mapNotNull null
                    if (number < 1) null else (season to number) to ep
                }
                val wanted = nextCoordinates(state.finishedSeason, state.finishedEpisode, candidates.map { it.first })
                val candidate = candidates.firstOrNull { it.first == wanted }
                if (candidate == null) {
                    updated[index] = state.copy(lastCheck = now, observedAbsent = true)
                    changed = true
                    return@forEach
                }
                val coordinates = candidate.first
                val season = coordinates.first
                val episode = coordinates.second
                val source = candidate.second
                if (isFuture(source.date, now) || source.data.isBlank()) {
                    // A real provider release timestamp is useful for the next scan, not proof of playback.
                    updated[index] = state.copy(lastCheck = now, observedAbsent = true)
                    changed = true
                    return@forEach
                }
                val resultEpisode = buildResultEpisode(
                    headerName = series.name, name = source.name,
                    poster = source.posterUrl ?: series.posterUrl, episode = episode,
                    seasonIndex = source.season, season = season, data = source.data,
                    apiName = series.apiName, id = "${series.apiName}|${state.parentId}|${source.data}".hashCode(),
                    index = 0, tvType = series.type, parentId = state.parentId,
                    description = source.description, airDate = source.date,
                )
                val generator = RepoLinkGenerator(listOf(resultEpisode), series)
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
                if (completed == true && hasLink) {
                    updated[index] = state.copy(
                        lastCheck = now, nextSeason = season, nextEpisode = episode,
                        nextPoster = source.posterUrl ?: series.posterUrl,
                        nextDescription = source.description,
                        newlyAvailable = state.observedAbsent,
                    )
                    changed = true
                } else {
                    // Broken network, DRM or captcha is indeterminate: do not advertise a new episode.
                    updated[index] = state.copy(lastCheck = now)
                    changed = true
                }
            }
            if (changed) {
                write(prefs, updated)
                val resume = HomeViewModel.getResumeWatching().orEmpty()
                context.applicationContext.addProgramsToContinueWatching(resume)
                Log.i(TAG, "refreshed tracked=${updated.size} announcements=${updated.count { it.nextEpisode != null }}")
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun read(prefs: android.content.SharedPreferences): List<Tracked> = runCatching {
        val items = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val state = Tracked(
                    parentId = item.getInt("parentId"), apiName = item.getString("apiName"),
                    seriesUrl = item.getString("seriesUrl"), title = item.getString("title"),
                    poster = item.optString("poster").takeIf(String::isNotBlank),
                    finishedSeason = item.getInt("finishedSeason"), finishedEpisode = item.getInt("finishedEpisode"),
                    completedAt = item.getLong("completedAt"), lastCheck = item.optLong("lastCheck"),
                    observedAbsent = item.optBoolean("observedAbsent"),
                    nextSeason = item.optInt("nextSeason").takeIf { it > 0 },
                    nextEpisode = item.optInt("nextEpisode").takeIf { it > 0 },
                    nextPoster = item.optString("nextPoster").takeIf(String::isNotBlank),
                    nextDescription = item.optString("nextDescription").takeIf(String::isNotBlank),
                    newlyAvailable = item.optBoolean("newlyAvailable"),
                )
                if (System.currentTimeMillis() - state.completedAt <= MAX_AGE) add(state)
            }
        }
    }.getOrDefault(emptyList())

    private fun write(prefs: android.content.SharedPreferences, states: List<Tracked>) {
        val items = JSONArray()
        states.sortedByDescending(Tracked::completedAt).take(30).forEach { state ->
            items.put(JSONObject().apply {
                put("parentId", state.parentId); put("apiName", state.apiName)
                put("seriesUrl", state.seriesUrl); put("title", state.title)
                put("poster", state.poster ?: "")
                put("finishedSeason", state.finishedSeason); put("finishedEpisode", state.finishedEpisode)
                put("completedAt", state.completedAt); put("lastCheck", state.lastCheck)
                put("observedAbsent", state.observedAbsent)
                put("nextSeason", state.nextSeason ?: 0); put("nextEpisode", state.nextEpisode ?: 0)
                put("nextPoster", state.nextPoster ?: ""); put("nextDescription", state.nextDescription ?: "")
                put("newlyAvailable", state.newlyAvailable)
            })
        }
        prefs.edit().putString(KEY, items.toString()).apply()
    }
}
