package com.andreassamitsch.joyntv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal data class JoynCompletedSeries(
    val seriesKey: String,
    val seriesTitle: String,
    val seriesId: String?,
    val seriesPath: String?,
    val seasonId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int,
    val completedAt: Long,
    val announcedVideoId: String? = null,
    /** A prior catalogue check saw no available follow-up; not inferred from TMDB air_date. */
    val observedWithoutNext: Boolean = false,
    val announcedAsNew: Boolean = false,
)

internal object JoynNextEpisodePolicy {
    /** Only a previously observed absence permits a NEW label on a subsequent catalogue find. */
    fun isNewlyAvailable(state: JoynCompletedSeries): Boolean =
        state.observedWithoutNext && state.announcedVideoId == null

    fun nextInSeason(
        completedEpisodeNumber: Int,
        episodes: List<JoynMediaItem>,
    ): JoynMediaItem? = episodes
        .filter { it.type == JoynMediaType.EPISODE && (it.episodeNumber ?: Int.MIN_VALUE) > completedEpisodeNumber }
        .minWithOrNull(compareBy<JoynMediaItem> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.id })

    fun firstEpisode(episodes: List<JoynMediaItem>): JoynMediaItem? = episodes
        .filter { it.type == JoynMediaType.EPISODE }
        .minWithOrNull(compareBy<JoynMediaItem> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.id })
}

internal class JoynNextEpisodeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): List<JoynCompletedSeries> = decode(prefs.getString(KEY_ITEMS, null))
        .filter { System.currentTimeMillis() - it.completedAt <= MAX_TRACKING_AGE_MS }
        .sortedByDescending(JoynCompletedSeries::completedAt)

    @Synchronized
    fun markCompleted(media: JoynMediaItem): JoynCompletedSeries? {
        if (media.type != JoynMediaType.EPISODE) return null
        val episodeNumber = media.episodeNumber ?: return null
        val seriesTitle = media.seriesTitle?.takeIf(String::isNotBlank) ?: return null
        val key = seriesKey(media) ?: return null
        val current = items().toMutableList()
        val previous = current.firstOrNull { it.seriesKey == key }
        current.removeAll { it.seriesKey == key }

        val previousSeason = previous?.seasonNumber
        val incomingSeason = media.seasonNumber
        if (
            previous != null &&
            previousSeason != null &&
            incomingSeason != null &&
            (incomingSeason < previousSeason ||
                (incomingSeason == previousSeason && episodeNumber < previous.episodeNumber))
        ) {
            return previous
        }
        val currentSeason = incomingSeason ?: previousSeason
        val effectiveEpisode = when {
            previous == null -> episodeNumber
            currentSeason != null && previousSeason != null && currentSeason > previousSeason -> episodeNumber
            else -> maxOf(episodeNumber, previous.episodeNumber)
        }
        val completed = JoynCompletedSeries(
            seriesKey = key,
            seriesTitle = seriesTitle,
            seriesId = media.seriesId ?: previous?.seriesId,
            seriesPath = media.seriesPath ?: previous?.seriesPath,
            seasonId = media.seasonId ?: previous?.seasonId,
            seasonNumber = currentSeason,
            episodeNumber = effectiveEpisode,
            completedAt = System.currentTimeMillis(),
            announcedVideoId = null,
        )
        current += completed
        persist(current)
        return completed
    }

    @Synchronized
    fun markAnnounced(seriesKey: String, videoId: String?, isNew: Boolean = false) {
        val current = items().map {
            if (it.seriesKey == seriesKey) it.copy(
                announcedVideoId = videoId,
                announcedAsNew = isNew,
                observedWithoutNext = false,
            ) else it
        }
        persist(current)
    }

    @Synchronized
    fun markNoNextAvailable(seriesKey: String) {
        val current = items().map {
            if (it.seriesKey == seriesKey) it.copy(
                announcedVideoId = null,
                announcedAsNew = false,
                observedWithoutNext = true,
            ) else it
        }
        persist(current)
    }

    companion object {
        private const val PREFS_NAME = "joyn_next_episode_v1"
        private const val KEY_ITEMS = "series"
        private const val MAX_ITEMS = 30
        private const val MAX_TRACKING_AGE_MS = 180L * 24L * 60L * 60L * 1000L

        fun seriesKey(media: JoynMediaItem): String? {
            val raw = media.seriesId
                ?: media.seriesPath
                ?: media.seriesTitle
                ?: return null
            return raw.trim().lowercase(Locale.ROOT)
        }

        private fun encode(items: List<JoynCompletedSeries>): String = JSONArray().apply {
            items.sortedByDescending(JoynCompletedSeries::completedAt).take(MAX_ITEMS).forEach { item ->
                put(
                    JSONObject()
                        .put("seriesKey", item.seriesKey)
                        .put("seriesTitle", item.seriesTitle)
                        .putNullable("seriesId", item.seriesId)
                        .putNullable("seriesPath", item.seriesPath)
                        .putNullable("seasonId", item.seasonId)
                        .putNullable("seasonNumber", item.seasonNumber)
                        .put("episodeNumber", item.episodeNumber)
                        .put("completedAt", item.completedAt)
                        .putNullable("announcedVideoId", item.announcedVideoId)
                        .put("observedWithoutNext", item.observedWithoutNext)
                        .put("announcedAsNew", item.announcedAsNew),
                )
            }
        }.toString()

        private fun decode(raw: String?): List<JoynCompletedSeries> = runCatching {
            if (raw.isNullOrBlank()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val key = json.optString("seriesKey").takeIf(String::isNotBlank) ?: continue
                    val title = json.optString("seriesTitle").takeIf(String::isNotBlank) ?: continue
                    val episode = json.optInt("episodeNumber").takeIf { it > 0 } ?: continue
                    add(
                        JoynCompletedSeries(
                            seriesKey = key,
                            seriesTitle = title,
                            seriesId = json.nullableString("seriesId"),
                            seriesPath = json.nullableString("seriesPath"),
                            seasonId = json.nullableString("seasonId"),
                            seasonNumber = json.nullableInt("seasonNumber"),
                            episodeNumber = episode,
                            completedAt = json.optLong("completedAt").takeIf { it > 0L } ?: 0L,
                            announcedVideoId = json.nullableString("announcedVideoId"),
                            observedWithoutNext = json.optBoolean("observedWithoutNext", false),
                            announcedAsNew = json.optBoolean("announcedAsNew", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

        private fun persistJson(prefs: android.content.SharedPreferences, items: List<JoynCompletedSeries>) {
            prefs.edit().putString(KEY_ITEMS, encode(items)).apply()
        }

        private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
            put(key, value ?: JSONObject.NULL)

        private fun JSONObject.nullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

        private fun JSONObject.nullableInt(key: String): Int? =
            if (isNull(key) || !has(key)) null else optInt(key)
    }

    private fun persist(items: List<JoynCompletedSeries>) {
        persistJson(prefs, items)
    }
}

internal class JoynNextEpisodeRefresher(private val context: Context) {
    private val repository = JoynRepository(context.applicationContext)
    private val store = JoynNextEpisodeStore(context.applicationContext)
    private val publisher = JoynWatchNextPublisher(context.applicationContext)

    suspend fun refresh() {
        store.items().forEach { state ->
            runCatching {
                val next = findNext(state)
                if (next == null) {
                    if (state.announcedVideoId != null) {
                        publisher.removeNextEpisode(state.seriesKey)
                    }
                    store.markNoNextAvailable(state.seriesKey)
                } else {
                    val enriched = next.copy(
                        seriesTitle = next.seriesTitle ?: state.seriesTitle,
                        seriesId = next.seriesId ?: state.seriesId,
                        seriesPath = next.seriesPath ?: state.seriesPath,
                    )
                    val nextVideoId = enriched.videoId ?: enriched.id
                    if (nextVideoId != state.announcedVideoId) {
                        val newlyAvailable = JoynNextEpisodePolicy.isNewlyAvailable(state)
                        publisher.publishNextEpisode(state.seriesKey, enriched, isNew = newlyAvailable)
                        store.markAnnounced(state.seriesKey, nextVideoId, isNew = newlyAvailable)
                    }
                }
            }
        }
    }

    private suspend fun findNext(state: JoynCompletedSeries): JoynMediaItem? {
        state.seasonId?.let { seasonId ->
            val sameSeason = repository.loadSeasonEpisodes(seasonId)
            JoynNextEpisodePolicy.nextInSeason(state.episodeNumber, sameSeason)?.let { return it }
        }

        val currentSeason = state.seasonNumber ?: return null
        val series = resolveSeries(state) ?: return null
        val details = repository.loadSeriesDetails(series)
        val laterSeasons = details.seasons.filter { it.number > currentSeason }.sortedBy { it.number }
        for (season in laterSeasons) {
            JoynNextEpisodePolicy.firstEpisode(repository.loadSeasonEpisodes(season.id))?.let { return it }
        }
        return null
    }

    private suspend fun resolveSeries(state: JoynCompletedSeries): JoynMediaItem? {
        state.seriesPath?.let { path ->
            return JoynMediaItem(
                id = state.seriesId ?: path,
                title = state.seriesTitle,
                path = path,
                type = JoynMediaType.SERIES,
                seriesId = state.seriesId,
                seriesPath = path,
            )
        }

        return repository.searchMedia(state.seriesTitle)
            .asSequence()
            .filter {
                it.type == JoynMediaType.SERIES &&
                    !it.path.isNullOrBlank() &&
                    it.title.equals(state.seriesTitle, ignoreCase = true)
            }
            .minByOrNull { it.title.length }
    }
}

internal object JoynNextEpisodeScheduler {
    private const val PERIODIC_WORK = "joyn-next-episode-periodic"
    private const val IMMEDIATE_WORK = "joyn-next-episode-now"

    fun install(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<JoynNextEpisodeWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<JoynNextEpisodeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

internal class JoynNextEpisodeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        JoynNextEpisodeRefresher(applicationContext).refresh()
        return Result.success()
    }
}
