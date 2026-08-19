package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists final playback state and republishes Android TV Watch Next without abusing
 * DataStoreHelper.setViewPosAndResume(). That upstream helper intentionally clears a Watched flag
 * when playback is persisted; an exit-sync must never do that.
 */
internal object ILauncherWatchNextSync {
    private const val TAG = "ILauncherWatchNext"
    private const val DUPLICATE_GUARD_MS = 750L
    private const val INTERNAL_HANDOFF_SUPPRESSION_MS = 2_500L
    private val lastFlushAt = AtomicLong(0L)
    private val suppressResumeMutationUntil = AtomicLong(0L)

    fun beginInternalHandoff() {
        suppressResumeMutationUntil.set(System.currentTimeMillis() + INTERNAL_HANDOFF_SUPPRESSION_MS)
    }

    fun cancelInternalHandoff() {
        suppressResumeMutationUntil.set(0L)
    }

    fun flush(
        context: Context?,
        stateId: Int?,
        position: Long?,
        duration: Long?,
        currentMeta: Any?,
        nextMeta: Any?,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastFlushAt.get()
        if (now - previous < DUPLICATE_GUARD_MS) return
        if (!lastFlushAt.compareAndSet(previous, now)) return

        val episode = currentMeta as? ResultEpisode
        val shouldPersistPosition =
            episode?.tvType?.isLiveStream() != true &&
                episode?.tvType != TvType.NSFW &&
                duration != null && duration > 0L &&
                position != null && position >= 0L

        if (shouldPersistPosition) {
            // Persist only the episode-local position. Do not call setViewPosAndResume(): it clears
            // VideoWatchState.Watched by design and can also move the series resume pointer.
            DataStoreHelper.setViewPos(stateId, position!!, duration!!)
        }

        if (now <= suppressResumeMutationUntil.get()) {
            Log.i(TAG, "flush reason=$reason resumeMutation=suppressed")
            return
        }

        if (shouldPersistPosition && episode != null) {
            updateResumePointer(
                episode = episode,
                nextEpisode = nextMeta as? ResultEpisode,
                position = position!!,
                duration = duration!!,
            )
        }

        publish(context, reason)
    }

    /** Called after CloudStream's episode menu marks the current resume episode as watched. */
    fun onMarkedWatched(
        context: Context?,
        episode: ResultEpisode,
        nextEpisode: ResultEpisode?,
    ) {
        ioSafe {
            try {
                val lastWatched = DataStoreHelper.getLastWatched(episode.parentId)
                val isCurrentResume = lastWatched?.episodeId == episode.id ||
                    (lastWatched?.season == episode.season && lastWatched?.episode == episode.episode)

                if (isCurrentResume) {
                    val next = nextEpisode?.takeIf {
                        it.parentId == episode.parentId && isLaterEpisode(it, episode)
                    }
                    if (next != null) {
                        DataStoreHelper.setLastWatched(
                            next.parentId,
                            next.id,
                            next.episode,
                            next.season,
                            isFromDownload = false,
                        )
                    } else {
                        DataStoreHelper.removeLastWatched(episode.parentId)
                    }
                }

                publishNow(context, "markedWatched")
            } catch (t: Throwable) {
                logError(t)
                Log.w(TAG, "watch-state sync failed")
            }
        }
    }

    private fun updateResumePointer(
        episode: ResultEpisode,
        nextEpisode: ResultEpisode?,
        position: Long,
        duration: Long,
    ) {
        val watched = DataStoreHelper.getVideoWatchState(episode.id) == VideoWatchState.Watched
        val percentage = if (duration > 0L) position * 100L / duration else 0L
        val shouldAdvance = watched || percentage >= NEXT_WATCH_EPISODE_PERCENTAGE
        val next = nextEpisode?.takeIf {
            it.parentId == episode.parentId && isLaterEpisode(it, episode)
        }
        val resume = if (shouldAdvance) next else episode

        if (resume != null) {
            DataStoreHelper.setLastWatched(
                resume.parentId,
                resume.id,
                resume.episode,
                resume.season,
                isFromDownload = false,
            )
        } else if (shouldAdvance) {
            DataStoreHelper.removeLastWatched(episode.parentId)
        }
    }

    private fun isLaterEpisode(candidate: ResultEpisode, current: ResultEpisode): Boolean {
        val candidateSeason = candidate.season ?: candidate.seasonIndex ?: 0
        val currentSeason = current.season ?: current.seasonIndex ?: 0
        return candidateSeason > currentSeason ||
            (candidateSeason == currentSeason && candidate.episode > current.episode)
    }

    private fun publish(context: Context?, reason: String) {
        if (!isLayout(TV) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ioSafe {
            try {
                publishNow(context, reason)
            } catch (t: Throwable) {
                logError(t)
                Log.w(TAG, "flush failed reason=$reason")
            }
        }
    }

    private suspend fun publishNow(context: Context?, reason: String) {
        if (!isLayout(TV) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context?.applicationContext ?: CloudStreamApp.context ?: return
        val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()
        appContext.addProgramsToContinueWatching(resumeWatching)
        Log.i(TAG, "flush reason=$reason count=${resumeWatching.size}")
    }
}
