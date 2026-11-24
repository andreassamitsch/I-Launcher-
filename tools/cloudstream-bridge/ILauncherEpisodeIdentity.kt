package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.DataStoreHelper

/** Matches ResultViewModel2's native TV-series numbering. Retain old IDs as migration sources. */
internal object ILauncherEpisodeIdentity {
    internal fun nativeId(parent: Int, rawSeason: Int?, number: Int): Int =
        parent + (rawSeason?.times(100_000) ?: 0) + number + 1

    internal fun legacyId(provider: String, parent: Int, rawIndex: Int, data: String): Int =
        "$provider|$parent|$rawIndex|$data".hashCode()

    internal data class Entry(val episode: Episode, val number: Int, val native: Int, val legacy: Int)

    fun catalogue(series: TvSeriesLoadResponse, parent: Int): List<Entry> =
        series.episodes.withIndex()
            .sortedBy { (it.value.season?.times(10_000) ?: 0) + (it.value.episode ?: 0) }
            .mapIndexed { sortedIndex, indexed ->
                val ep = indexed.value
                val number = ep.episode ?: (sortedIndex + 1)
                Entry(ep, number, nativeId(parent, ep.season, number),
                    legacyId(series.apiName, parent, indexed.index, ep.data))
            }

    internal fun mayCopy(nativePresent: Boolean, legacyPresent: Boolean, migrated: Boolean): Boolean =
        !nativePresent && legacyPresent && !migrated

    @Synchronized
    fun migrate(series: TvSeriesLoadResponse, parent: Int) {
        val prefs = CloudStreamApp.context?.getSharedPreferences("ilauncher_episode_identity_v1",
            android.content.Context.MODE_PRIVATE) ?: return
        val account = DataStoreHelper.currentAccount
        val catalogue = catalogue(series, parent)
        val counts = catalogue.groupingBy { it.native }.eachCount()
        val legacyCounts = catalogue.groupingBy { it.legacy }.eachCount()
        val seasons = series.seasonNames?.associate { it.season to it.displaySeason }.orEmpty()
        for (entry in catalogue) {
            if (DataStoreHelper.currentAccount != account) return
            // Numberless episodes and colliding IDs are ambiguous. Never copy their old data.
            if (entry.episode.episode == null || entry.native == entry.legacy ||
                counts[entry.native] != 1 || legacyCounts[entry.legacy] != 1 ||
                catalogue.any { it.native == entry.legacy }) continue
            val key = "$account:${series.apiName}:$parent:${entry.native}:${entry.legacy}"
            val migrated = prefs.getBoolean(key, false)
            if (migrated) continue
            val oldPos = DataStoreHelper.getViewPos(entry.legacy)
            val oldState = DataStoreHelper.getVideoWatchState(entry.legacy)
            val nativePos = DataStoreHelper.getViewPos(entry.native)
            val nativeState = DataStoreHelper.getVideoWatchState(entry.native)
            if (mayCopy(nativePos != null || nativeState != null, oldPos != null || oldState != null, migrated)) {
                oldPos?.let { DataStoreHelper.setViewPos(entry.native, it.position, it.duration) }
                oldState?.let { DataStoreHelper.setVideoWatchState(entry.native, it) }
            }
            val resume = DataStoreHelper.getLastWatched(parent)
            val displaySeason = entry.episode.season?.let { seasons[it] ?: it }
            if (resume != null && !resume.isFromDownload && resume.episodeId == entry.legacy &&
                resume.season == displaySeason && resume.episode == entry.number) {
                DataStoreHelper.setLastWatched(parent, entry.native, entry.number, displaySeason,
                    isFromDownload = false, updateTime = resume.updateTime)
            }
            // Mark even if no legacy data: later explicit native changes must not be overwritten.
            prefs.edit().putBoolean(key, true).apply()
        }
    }
}
