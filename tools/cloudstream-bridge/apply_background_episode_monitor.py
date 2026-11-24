#!/usr/bin/env python3
"""Add a bounded WorkManager refresh and isolate episode monitor records by account."""
from pathlib import Path
import shutil
import sys

def patch(path, old, new):
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"{path}: unexpected background monitor anchor {old[:100]}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    shutil.copyfile(Path(__file__).with_name("ILauncherEpisodeWorker.kt"), base / "services/ILauncherEpisodeWorker.kt")
    patch(base / "MainActivity.kt", "        updateTv()\n\n        // backup when we update the app",
          "        updateTv()\n        com.lagradost.cloudstream3.services.ILauncherEpisodeWorker.schedule(this)\n\n        // backup when we update the app")
    monitor = base / "ui/player/ILauncherNextEpisodeMonitor.kt"
    patch(monitor, "import kotlinx.coroutines.sync.Mutex",
          "import com.lagradost.cloudstream3.utils.DataStoreHelper\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.sync.Mutex")
    patch(monitor, "    fun recordCompleted(context: Context?, episode: ResultEpisode) {",
          """    private fun accountPrefs(context: Context, account: String): android.content.SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS + "_" + account, Context.MODE_PRIVATE)
        synchronized(this) {
            if (!prefs.getBoolean("legacy_checked", false)) {
                val editor = prefs.edit()
                // The old store had no account identity. Only migrate when the default account
                // is the sole local account; otherwise retain the legacy store untouched.
                if (account == "0" && DataStoreHelper.getAccounts(context).size == 1) {
                    val old = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (!prefs.contains(KEY)) old.getString(KEY, null)?.let { editor.putString(KEY, it) }
                }
                editor.putBoolean("legacy_checked", true).apply()
            }
        }
        return prefs
    }

    fun dueProviders(context: Context, account: String): List<String> {
        val now = System.currentTimeMillis()
        return read(accountPrefs(context, account))
            .filter { isDue(it, now) }
            .sortedBy { it.lastCheck }.take(6).map { it.apiName }.distinct()
    }

    fun recordCompleted(context: Context?, episode: ResultEpisode) {""")
    patch(monitor, "val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)",
          "val prefs = accountPrefs(appContext, DataStoreHelper.currentAccount)")
    patch(monitor, "read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))",
          "read(accountPrefs(context, DataStoreHelper.currentAccount))")
    patch(monitor, "val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)",
          "val account = DataStoreHelper.currentAccount\n            val prefs = accountPrefs(context, account)")
    patch(monitor, "            val updated = read(prefs).toMutableList()",
          """            val original = read(prefs)
            val updated = original.toMutableList()
            // For old active resumes, a matching title only nominates a metadata lookup.
            // The actual merge always requires a verified external ID. Share the scan budget.
            var identityLoads = 0
            identityAttempts.entries.removeIf { it.value + REFRESH_INTERVAL <= now }
            for (card in resumeCards) {
                if (identityLoads >= 2 || DataStoreHelper.currentAccount != account) break
                val parent = card.parentId ?: continue
                if (card.type != com.lagradost.cloudstream3.TvType.TvSeries || card.isFromDownload ||
                    card.apiName !in active || ILauncherSeriesIdentity.cachedIdentity(parent, card.apiName) != null ||
                    original.none { it.nextEpisode != null && it.title.trim().equals(card.name.trim(), ignoreCase = true) }) continue
                val key = "$account|${card.apiName}|$parent"
                val api = apis.withLock { apis.firstOrNull { it.name == card.apiName } } ?: continue
                if (identityAttempts.putIfAbsent(key, now) != null) continue
                identityLoads++
                val result = withTimeoutOrNull(10_000) { APIRepository(api).load(card.url) }
                val series = (result as? Resource.Success<*>)?.value as? TvSeriesLoadResponse
                if (series != null && series.apiName == card.apiName && DataStoreHelper.currentAccount == account) {
                    ILauncherSeriesIdentity.remember(parent, series)
                    if (ILauncherSeriesIdentity.cachedIdentity(parent, card.apiName) != null) changed = true
                }
            }""")
    patch(monitor, "            }.take(6).forEach { index ->\n                val state = updated[index]",
          "            }.sortedBy { updated[it].lastCheck }.take((6 - identityLoads).coerceAtLeast(0)).forEach { index ->\n                currentCoroutineContext().ensureActive()\n                if (DataStoreHelper.currentAccount != account) return\n                val state = updated[index]")
    patch(monitor, """            if (changed) {
                write(prefs, updated)
                val resume = HomeViewModel.getResumeWatching().orEmpty()
                context.applicationContext.addProgramsToContinueWatching(resume)""",
          """            currentCoroutineContext().ensureActive()
            if (changed && DataStoreHelper.currentAccount == account) {
                // Do not overwrite a newer completion recorded while the network request was running.
                synchronized(this) {
                    val fresh = read(prefs)
                    val merged = fresh.map { state ->
                        val index = original.indexOf(state)
                        if (index >= 0) updated[index] else state
                    }
                    write(prefs, merged)
                }
                val resume = HomeViewModel.getResumeWatching().orEmpty()
                if (DataStoreHelper.currentAccount != account) return
                context.applicationContext.addProgramsToContinueWatching(resume)""")

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
