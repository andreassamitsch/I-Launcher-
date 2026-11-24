package com.lagradost.cloudstream3.services

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Bounded, silent Watch Next refresh. Uses only already installed provider files. */
class ILauncherEpisodeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    companion object {
        private const val NAME = "ilauncher_watch_next_periodic_v1"

        fun schedule(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isLayout(TV)) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequest.Builder(ILauncherEpisodeWorker::class.java, 6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        context.updateTv()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isLayout(TV)) return Result.success()
        val account = DataStoreHelper.currentAccount
        val due = ILauncherNextEpisodeMonitor.dueProviders(context, account)
        if (due.isEmpty()) return Result.success()
        MainActivity.setLastError(context)
        if (PluginManager.isSafeMode()) return Result.success()
        try {
            val completed = withTimeoutOrNull(240_000L) {
                // A live Activity initializes the plugin runtime itself. Never reload it underneath playback.
                if (CommonActivity.activity == null) {
                    app.initClient(context, ignoreSSL = false)
                    val settings = SettingsJson()
                    settings.enableAdult = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(context.getString(R.string.enable_nsfw_on_providers_key), false)
                    MainAPI.settingsForProvider = settings
                    for (provider in due) {
                        if (DataStoreHelper.currentAccount != account || CommonActivity.activity != null) break
                        if (apis.withLock { apis.none { it.name == provider } }) {
                            withTimeoutOrNull(15_000L) { PluginManager.loadSinglePlugin(context, provider) }
                        }
                    }
                }
                if (DataStoreHelper.currentAccount == account) {
                    ILauncherNextEpisodeMonitor.refresh(context)
                }
                true
            }
            Log.i("ILauncherEpisodeWorker", "completed=${completed == true} providers=${due.size}")
            // No immediate retry loop. The next scheduled pass will retry missing providers.
            return Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w("ILauncherEpisodeWorker", "refresh failed: ${e.javaClass.simpleName}")
            return Result.success()
        }
    }
}
