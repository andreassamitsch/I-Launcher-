#!/usr/bin/env python3
"""Wait for the requested extension, not the first API registered during app startup."""
from pathlib import Path
import shutil
import sys

def replace_once(path, old, new):
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"{path}: expected one anchor: {old[:120]}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    plugins = base / "plugins/PluginManager.kt"
    # loadedOnlinePlugins is only set after the optional network update; it is
    # never set by the auto-update-disabled local load path. Track completion
    # of the actual initial online extension load in both modes.
    replace_once(plugins, """    var loadedLocalPlugins = false
        private set""", """    @Volatile
    var loadedLocalPlugins = false
        private set

    @Volatile
    internal var bridgeInitialOnlinePluginsLoaded = false
        private set""")
    replace_once(plugins, """    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        (getPluginsOnline()).toList().amap { pluginData ->
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
        }
    }""", """    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        (getPluginsOnline()).toList().amap { pluginData ->
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
        }
        bridgeInitialOnlinePluginsLoaded = true
    }""")
    bridge = base / "ILauncherDirectPlay.kt"
    replace_once(bridge, "                val providers = awaitProviders(activity)", """                val startup = awaitProviders(activity, request, loading, launchToken)
                val providers = startup.first""")
    replace_once(bridge, """                if (orderedProviders.isEmpty()) {
                    Log.w(TAG, "providers active=0 setupRequired=true")
                    loading.dismiss()
                    main { ILauncherBridgeSetup.showNoProvidersDialog(activity) }
                    return@ioSafe
                }""", """                if (orderedProviders.isEmpty()) {
                    Log.w(TAG, "provider unavailable decision=${startup.second} pinned=${request.providerName != null}")
                    loading.dismiss()
                    main {
                        if (ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken) &&
                            !loading.isCancelled() && !activity.isFinishing && !activity.isDestroyed) {
                            if (startup.second == ILauncherProviderStartup.Decision.NoExtensions) {
                                ILauncherBridgeSetup.showNoProvidersDialog(activity)
                            } else {
                                ILauncherBridgeSetup.showProviderUnavailableDialog(activity, request.providerName) {
                                    handle(activity, rawUri)
                                }
                            }
                        }
                    }
                    return@ioSafe
                }""")
    source = bridge.read_text(encoding="utf-8")
    start = source.index("    private suspend fun awaitProviders(activity: FragmentActivity): List<MainAPI> {")
    end = source.index("    private fun orderProviders(", start)
    source = source[:start] + """    private suspend fun awaitProviders(
        activity: FragmentActivity,
        request: Request,
        loading: ILauncherBridgeLoading,
        launchToken: Long,
    ): Pair<List<MainAPI>, ILauncherProviderStartup.Decision> {
        val manager = com.lagradost.cloudstream3.plugins.PluginManager
        for (attempt in 0..PROVIDER_STARTUP_WAIT_STEPS) {
            if (loading.isCancelled() || activity.isFinishing || activity.isDestroyed ||
                !ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                return emptyList<MainAPI>() to ILauncherProviderStartup.Decision.Unavailable
            }
            val activeNames = activity.getApiSettings()
            val current = apis.withLock {
                apis.filter { api -> activeNames.contains(api.name) }.distinctBy { it.name }
            }
            val complete = manager.bridgeInitialOnlinePluginsLoaded && manager.loadedLocalPlugins
            val installed = if (complete) manager.getPluginsOnline().size + manager.getPluginsLocal().size else 0
            val decision = ILauncherProviderStartup.decide(
                pinned = request.providerName,
                active = current.map { it.name }.toSet(),
                initialLoadComplete = complete,
                installedCount = installed,
                deadlineReached = attempt == PROVIDER_STARTUP_WAIT_STEPS,
                safeMode = manager.isSafeMode(),
            )
            if (decision != ILauncherProviderStartup.Decision.Wait) {
                Log.i(TAG, "provider startup decision=$decision active=${current.size} complete=$complete pinned=${request.providerName != null} waitedMs=${attempt * PROVIDER_STARTUP_POLL_MS}")
                return (if (decision == ILauncherProviderStartup.Decision.Ready) current else emptyList()) to decision
            }
            if (attempt == 0 && request.providerName != null) {
                loading.update("Provider ${request.providerName} wird geladen …")
            }
            delay(PROVIDER_STARTUP_POLL_MS)
        }
        return emptyList<MainAPI>() to ILauncherProviderStartup.Decision.Unavailable
    }

""" + source[end:]
    bridge.write_text(source, encoding="utf-8")
    here = Path(__file__).parent
    shutil.copyfile(here / "ILauncherProviderStartup.kt", base / "ILauncherProviderStartup.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherProviderStartupTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(here / test.name, test)

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
