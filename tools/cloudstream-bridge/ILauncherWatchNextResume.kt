package com.lagradost.cloudstream3

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.delay
import java.util.UUID

/** A bridge resume ticket survives result reloads, not a different launch or process. */
internal object ILauncherWatchNextResume {
    private const val TAG = "ILauncherWatchNextResume"
    private val gate = ResumeGate()
    private data class Pending(val ticket: String, val token: Long, val account: String)
    @Volatile private var pending: Pending? = null

    internal class ResumeGate {
        private var ticket: String? = null
        @Synchronized fun register(value: String) { ticket = value }
        @Synchronized fun isPending(value: String): Boolean = ticket == value
        @Synchronized fun claim(value: String): Boolean {
            if (ticket != value) return false
            ticket = null
            return true
        }
    }

    fun isPending(ticket: String): Boolean {
        val state = pending ?: return false
        return state.ticket == ticket && gate.isPending(ticket) &&
            state.account == DataStoreHelper.currentAccount.toString() &&
            ILauncherBridgeNavigation.isCurrentExternalLaunch(state.token)
    }

    @Synchronized fun claim(ticket: String): Boolean =
        isPending(ticket) && gate.claim(ticket)

    internal data class Coordinate(val id: Int, val season: Int?, val episode: Int?)
    internal fun select(rows: List<Coordinate>, id: Int?, season: Int?, episode: Int?): Int? {
        val unique = rows.distinct()
        fun agrees(row: Coordinate): Boolean =
            (season == null || row.season == season) && (episode == null || row.episode == episode)
        if (id != null) unique.singleOrNull { it.id == id && agrees(it) }?.let { return it.id }
        // Without a full coordinate pair, never guess a different episode.
        if (season == null || episode == null) return null
        return unique.filter(::agrees).map { it.id }.distinct().singleOrNull()
    }

    fun start(activity: FragmentActivity, target: ILauncherWatchNextIntent.Target) {
        val token = ILauncherBridgeNavigation.beginExternalLaunch(activity)
        val account = DataStoreHelper.currentAccount.toString()
        val loading = ILauncherBridgeLoading.show(activity, "Wiedergabe wird vorbereitet …")
        fun valid(): Boolean = !loading.isCancelled() && !activity.isFinishing && !activity.isDestroyed &&
            account == DataStoreHelper.currentAccount.toString() &&
            ILauncherBridgeNavigation.isCurrentExternalLaunch(token)
        ioSafe {
            try {
                val cards = HomeViewModel.getResumeWatching().orEmpty()
                val card = ILauncherWatchNextIntent.resolve(cards, target)
                if (card == null) {
                    Log.w(TAG, "resume target not found parent=${target.seriesKey}")
                    return@ioSafe
                }
                Log.i(TAG, "resume resolved provider=${card.apiName} id=${card.id} season=${card.season} episode=${card.episode}")
                loading.update("Provider ${card.apiName} wird geladen …")
                for (attempt in 0..360) {
                    if (!valid()) return@ioSafe
                    if (APIHolder.getApiFromNameNull(card.apiName) != null) {
                        ILauncherBridgeNavigation.navigateExternal(activity, token) {
                            if (valid()) {
                                val ticket = UUID.randomUUID().toString()
                                pending = Pending(ticket, token, account)
                                gate.register(ticket)
                                Log.i(TAG, "resume handed to result waitedMs=${attempt * 250L}")
                                activity.loadSearchResult(card, START_ACTION_LOAD_EP, card.id, ticket)
                            }
                        }
                        return@ioSafe
                    }
                    val done = PluginManager.bridgeInitialOnlinePluginsLoaded && PluginManager.loadedLocalPlugins
                    if (done || PluginManager.isSafeMode() || attempt == 360) {
                        Log.w(TAG, "resume provider unavailable complete=$done")
                        main {
                            if (valid()) ILauncherBridgeSetup.showProviderUnavailableDialog(activity, card.apiName) {
                                if (ILauncherBridgeNavigation.isCurrentExternalLaunch(token)) start(activity, target)
                            }
                        }
                        return@ioSafe
                    }
                    delay(250)
                }
            } finally {
                loading.dismiss()
            }
        }
    }
}
