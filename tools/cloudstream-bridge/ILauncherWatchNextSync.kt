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
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Flushes the current player position to CloudStream's local resume state and then republishes the
 * Android TV Watch Next rows when the player is left/backgrounded.
 *
 * Upstream a72f9e6 normally republishes Watch Next from HomeViewModel.loadResumeWatching(), which
 * means leaving the player on a result/detail page can leave Android's TvProvider stale until the
 * CloudStream home screen is visited. This helper closes that lifecycle gap without moving Watch
 * Next ownership into I Launcher.
 */
internal object ILauncherWatchNextSync {
    private const val TAG = "ILauncherWatchNext"
    private const val DUPLICATE_GUARD_MS = 750L
    private val lastFlushAt = AtomicLong(0L)

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
                duration != null && duration > 0L && position != null

        if (shouldPersistPosition) {
            DataStoreHelper.setViewPosAndResume(
                stateId,
                position!!,
                duration!!,
                currentMeta,
                nextMeta,
            )
        }

        if (!isLayout(TV) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context?.applicationContext ?: CloudStreamApp.context ?: return

        ioSafe {
            try {
                val resumeWatching = HomeViewModel.getResumeWatching() ?: return@ioSafe
                appContext.addProgramsToContinueWatching(resumeWatching)
                Log.i(TAG, "flush reason=$reason count=${resumeWatching.size}")
            } catch (t: Throwable) {
                logError(t)
                Log.w(TAG, "flush failed reason=$reason")
            }
        }
    }
}
