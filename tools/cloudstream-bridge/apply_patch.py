#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path

BASE_COMMIT = "a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}: found {count}\n--- needle ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def require_text(path: Path, needle: str, description: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        raise RuntimeError(f"Missing required {description} in {path}:\n{needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_patch.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app" / "src" / "main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    # 1) Reproduce the complete Watch Next fix from the device-tested
    # CloudStream-WatchNext-Test-a72f9e6.apk.
    #
    # The upstream code builds timeStampHashMap keyed by lastWatched.parentId, therefore it must
    # also read that map with episodeInfo.parentId. Reading it with episodeInfo.id drops the real
    # engagement timestamp and causes Android TV's Watch Next row to appear in the wrong order.
    #
    # The lookup already uses customId, so the stored internalProviderId must use the exact same
    # value. Otherwise every refresh misses the existing TvProvider row and republishes it.
    app_context = root / "app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt"
    replace_once(
        app_context,
        """    private fun buildWatchNextProgramUri(\n        context: Context,\n        card: DataStoreHelper.ResumeWatchingResult,\n        resumeWatching: DownloadObjects.ResumeWatching?\n    ): WatchNextProgram {""",
        """    private fun buildWatchNextProgramUri(\n        context: Context,\n        card: DataStoreHelper.ResumeWatchingResult,\n        resumeWatching: DownloadObjects.ResumeWatching?,\n        customId: String,\n    ): WatchNextProgram {""",
    )
    replace_once(app_context, ".setInternalProviderId(card.url)", ".setInternalProviderId(customId)")
    replace_once(
        app_context,
        """                    val nextProgram = buildWatchNextProgramUri(\n                        context,\n                        episodeInfo,\n                        timeStampHashMap[episodeInfo.id]\n                    )""",
        """                    val nextProgram = buildWatchNextProgramUri(\n                        context,\n                        episodeInfo,\n                        timeStampHashMap[episodeInfo.parentId],\n                        customId,\n                    )""",
    )

    # Guard the exact two device-tested Watch Next invariants. This deliberately fails the bridge
    # build if a future refactor drops either part of the fix.
    require_text(
        app_context,
        "timeStampHashMap[episodeInfo.parentId]",
        "Watch Next parentId timestamp lookup",
    )
    require_text(
        app_context,
        ".setInternalProviderId(customId)",
        "Watch Next stable internalProviderId",
    )

    # 2) Add the I Launcher direct-play resolver and its pure protocol tests to CloudStream.
    bridge_source = Path(__file__).with_name("ILauncherDirectPlay.kt")
    bridge_target = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherDirectPlay.kt"
    shutil.copyfile(bridge_source, bridge_target)
    bridge_setup_source = Path(__file__).with_name("ILauncherBridgeSetup.kt")
    bridge_setup_target = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherBridgeSetup.kt"
    shutil.copyfile(bridge_setup_source, bridge_setup_target)
    bridge_loading_source = Path(__file__).with_name("ILauncherBridgeLoading.kt")
    bridge_loading_target = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherBridgeLoading.kt"
    shutil.copyfile(bridge_loading_source, bridge_loading_target)
    bridge_test_source = Path(__file__).with_name("ILauncherDirectPlayTest.kt")
    bridge_test_target = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherDirectPlayTest.kt"
    bridge_test_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(bridge_test_source, bridge_test_target)

    # The Watch Next publisher in upstream a72f9e6 normally runs when HomeViewModel refreshes its
    # resume row. If playback is left directly to a result page or the app is backgrounded, the
    # local resume state can already be correct while Android TvProvider still contains the old
    # progress/timestamp. Add a small player-exit hook which persists the final position and
    # republishes CloudStream's Watch Next rows without requiring a visit to the CloudStream home.
    watch_next_sync_source = Path(__file__).with_name("ILauncherWatchNextSync.kt")
    watch_next_sync_target = root / "app/src/main/java/com/lagradost/cloudstream3/ui/player/ILauncherWatchNextSync.kt"
    shutil.copyfile(watch_next_sync_source, watch_next_sync_target)
    generator_player = root / "app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
    replace_once(
        generator_player,
        """    override fun onDestroy() {\n        ResultFragment.updateUI()\n        currentVerifyLink?.cancel()\n        super.onDestroy()\n    }""",
        """    private fun flushWatchNextOnExit(reason: String) {\n        ILauncherWatchNextSync.flush(\n            context = context,\n            stateId = viewModel.state.generatorState?.id,\n            position = player.getPosition(),\n            duration = player.getDuration(),\n            currentMeta = currentMeta,\n            nextMeta = nextMeta,\n            reason = reason,\n        )\n    }\n\n    override fun onStop() {\n        // Persist the final playback position and publish it to Android TV before the player is\n        // stopped. This covers Back-to-details and leaving CloudStream from a running player.\n        flushWatchNextOnExit(\"onStop\")\n        super.onStop()\n    }\n\n    override fun onDestroy() {\n        // Lifecycle safety net for teardown paths where onStop was skipped. The helper suppresses\n        // the normal onStop -> onDestroy duplicate.\n        flushWatchNextOnExit(\"onDestroy\")\n        ResultFragment.updateUI()\n        currentVerifyLink?.cancel()\n        super.onDestroy()\n    }""",
    )
    require_text(
        generator_player,
        "flushWatchNextOnExit(\"onStop\")",
        "player-exit Watch Next sync",
    )
    require_text(
        generator_player,
        "flushWatchNextOnExit(\"onDestroy\")",
        "player-destroy Watch Next safety sync",
    )

    # A prerelease/debug CloudStream package has its own Android sandbox. If that package has no
    # extensions configured, silently opening an empty search is misleading: the same extensions
    # installed in the official CloudStream package are not visible here. Surface that state and
    # take the user straight to this package's Extensions screen instead.
    replace_once(
        bridge_target,
        """                Log.i(TAG, \"providers active=${orderedProviders.size}\")\n\n                if (request.providerSelection == ProviderSelection.Choose) {""",
        """                Log.i(TAG, \"providers active=${orderedProviders.size}\")\n\n                if (orderedProviders.isEmpty()) {\n                    Log.w(TAG, \"providers active=0 setupRequired=true\")\n                    main { ILauncherBridgeSetup.showNoProvidersDialog(activity) }\n                    return@ioSafe\n                }\n\n                if (request.providerSelection == ProviderSelection.Choose) {""",
    )

    # Provider UI must be immediate. The previous chooser resolved every provider before showing
    # anything, so long-OK could block for many seconds. Show all active providers immediately and
    # resolve only the selected provider on demand. The automatic short-OK path remains unchanged.
    replace_once(
        bridge_target,
        """                if (request.providerSelection == ProviderSelection.Choose) {\n                    val matches = resolveAll(orderedProviders, request)\n                    main {\n                        if (matches.isEmpty()) {\n                            showNoDirectMatchDialog(activity, request, providers)\n                        } else {\n                            showProviderChooser(activity, request, providers, matches)\n                        }\n                    }\n                    return@ioSafe\n                }""",
        """                if (request.providerSelection == ProviderSelection.Choose) {\n                    main { showProviderChooser(activity, request, providers, orderedProviders) }\n                    return@ioSafe\n                }""",
    )
    replace_once(
        bridge_target,
        """        matches: List<Match>,\n    ) {\n        val labels = matches.map { it.response.apiName }.toTypedArray()""",
        """        providers: List<MainAPI>,\n    ) {\n        val labels = providers.map { it.name }.toTypedArray()""",
    )
    replace_once(
        bridge_target,
        """            .setSingleChoiceItems(labels, -1) { openedDialog, which ->\n                val match = matches.getOrNull(which) ?: return@setSingleChoiceItems\n                openedDialog.dismiss()\n                executeSelectedMatch(activity, request, match)\n            }""",
        """            .setSingleChoiceItems(labels, -1) { openedDialog, which ->\n                val provider = providers.getOrNull(which) ?: return@setSingleChoiceItems\n                openedDialog.dismiss()\n                val selectedLoading = ILauncherBridgeLoading.show(activity, \"Prüfe ${provider.name} …\")\n                ioSafe {\n                    val match = resolveProvider(provider, request)\n                    if (selectedLoading.isCancelled()) return@ioSafe\n                    main {\n                        if (match == null) {\n                            selectedLoading.dismiss()\n                            showNoDirectMatchDialog(activity, request, allProviders)\n                        } else {\n                            executeSelectedMatch(activity, request, match, selectedLoading)\n                        }\n                    }\n                }\n            }""",
    )

    # AlertDialog does not render a message and a single-choice list together in the same content
    # area on this CloudStream/AppCompat version. Keeping setMessage() therefore hid the provider
    # rows completely (only the move buttons were visible). The list is the primary TV UI here.
    replace_once(
        bridge_target,
        """        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)\n            .setTitle(\"Provider-Priorität\")\n            .setMessage(\"Kurzes OK versucht zuerst den zuletzt wirklich abspielbaren Anbieter für diesen Inhalt, danach diese Reihenfolge. Ist ein Anbieter nicht verfügbar, wird automatisch der nächste versucht.\")\n            .setSingleChoiceItems(adapter, selected) { _, which -> selected = which }""",
        """        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)\n            .setTitle(\"Provider-Priorität\")\n            .setSingleChoiceItems(adapter, selected) { _, which -> selected = which }""",
    )

    # Show a visible loading surface immediately for any bridge handoff. It remains present while
    # extensions are starting, providers are matched and playable links are resolved. The user may
    # cancel it; cancellation suppresses later fallback/navigation.
    replace_once(
        bridge_target,
        """        Log.i(TAG, \"start kind=${request.kind} selection=${request.providerSelection} identity=$identity\")\n\n        ioSafe {""",
        """        Log.i(TAG, \"start kind=${request.kind} selection=${request.providerSelection} identity=$identity\")\n        val loading = ILauncherBridgeLoading.show(activity, \"CloudStream wird vorbereitet …\")\n\n        ioSafe {""",
    )
    replace_once(
        bridge_target,
        """            try {\n                val providers = awaitProviders(activity)""",
        """            try {\n                loading.update(\"Provider werden geladen …\")\n                val providers = awaitProviders(activity)""",
    )
    replace_once(
        bridge_target,
        """                if (orderedProviders.isEmpty()) {\n                    Log.w(TAG, \"providers active=0 setupRequired=true\")\n                    main { ILauncherBridgeSetup.showNoProvidersDialog(activity) }\n                    return@ioSafe\n                }""",
        """                if (loading.isCancelled()) return@ioSafe\n                if (orderedProviders.isEmpty()) {\n                    Log.w(TAG, \"providers active=0 setupRequired=true\")\n                    loading.dismiss()\n                    main { ILauncherBridgeSetup.showNoProvidersDialog(activity) }\n                    return@ioSafe\n                }""",
    )
    replace_once(
        bridge_target,
        """                if (request.providerSelection == ProviderSelection.Choose) {\n                    main { showProviderChooser(activity, request, providers, orderedProviders) }\n                    return@ioSafe\n                }\n\n                if (!resolveAndExecuteAutomatic(activity, orderedProviders, request)) {\n                    Log.i(TAG, \"automatic miss identity=$identity; opening CloudStream search\")\n                    fallbackToSearch(activity, request.title)\n                }""",
        """                if (request.providerSelection == ProviderSelection.Choose) {\n                    loading.dismiss()\n                    main { showProviderChooser(activity, request, providers, orderedProviders) }\n                    return@ioSafe\n                }\n\n                val executed = resolveAndExecuteAutomatic(activity, orderedProviders, request, loading)\n                if (loading.isCancelled()) return@ioSafe\n                if (!executed) {\n                    loading.dismiss()\n                    Log.i(TAG, \"automatic miss identity=$identity; opening CloudStream search\")\n                    fallbackToSearch(activity, request.title)\n                }""",
    )
    replace_once(
        bridge_target,
        """            } catch (t: Throwable) {\n                logError(t)\n                Log.w(TAG, \"bridge failed identity=$identity; opening CloudStream search\")\n                fallbackToSearch(activity, request.title)\n            }""",
        """            } catch (t: Throwable) {\n                logError(t)\n                loading.dismiss()\n                if (!loading.isCancelled()) {\n                    Log.w(TAG, \"bridge failed identity=$identity; opening CloudStream search\")\n                    fallbackToSearch(activity, request.title)\n                }\n            }""",
    )
    replace_once(
        bridge_target,
        """    private suspend fun resolveAndExecuteAutomatic(\n        activity: FragmentActivity,\n        providers: List<MainAPI>,\n        request: Request,\n    ): Boolean = coroutineScope {""",
        """    private suspend fun resolveAndExecuteAutomatic(\n        activity: FragmentActivity,\n        providers: List<MainAPI>,\n        request: Request,\n        loading: ILauncherBridgeLoading,\n    ): Boolean = coroutineScope {""",
    )
    replace_once(
        bridge_target,
        """        for ((index, job) in jobs.withIndex()) {\n            val match = job.await() ?: continue""",
        """        for ((index, job) in jobs.withIndex()) {\n            if (loading.isCancelled()) {\n                jobs.forEach { it.cancel() }\n                return@coroutineScope false\n            }\n            loading.update(\"Prüfe ${providers[index].name} …\")\n            val match = job.await() ?: continue""",
    )
    replace_once(
        bridge_target,
        """            if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {\n                jobs.drop(index + 1).forEach { it.cancel() }\n                openResolvedDetails(activity, match.response)""",
        """            if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {\n                jobs.drop(index + 1).forEach { it.cancel() }\n                loading.update(\"Öffne ${match.response.apiName} …\")\n                loading.dismiss()\n                openResolvedDetails(activity, match.response)""",
    )
    replace_once(
        bridge_target,
        """            val prepared = preparePlayback(match.response, request) ?: continue\n            if (hasPlayableLinks(prepared)) {\n                jobs.drop(index + 1).forEach { it.cancel() }\n                launchPreparedPlayback(activity, prepared)""",
        """            val prepared = preparePlayback(match.response, request) ?: continue\n            loading.update(\"Stream wird über ${prepared.providerName} aufgelöst …\")\n            if (hasPlayableLinks(prepared)) {\n                jobs.drop(index + 1).forEach { it.cancel() }\n                loading.update(\"Wiedergabe wird gestartet …\")\n                loading.dismiss()\n                launchPreparedPlayback(activity, prepared)""",
    )
    replace_once(
        bridge_target,
        """    private fun executeSelectedMatch(activity: FragmentActivity, request: Request, match: Match) {\n        if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {\n            openResolvedDetails(activity, match.response)\n            rememberLastProvider(activity, request, match.response.apiName)\n            return\n        }\n\n        ioSafe {\n            val prepared = preparePlayback(match.response, request)\n            if (prepared != null && hasPlayableLinks(prepared)) {\n                launchPreparedPlayback(activity, prepared)\n                rememberLastProvider(activity, request, prepared.providerName)\n            } else {\n                main { showNoPlayableLinkDialog(activity, request, match.response.apiName) }\n            }\n        }\n    }""",
        """    private fun executeSelectedMatch(\n        activity: FragmentActivity,\n        request: Request,\n        match: Match,\n        loading: ILauncherBridgeLoading? = null,\n    ) {\n        if (request.kind == MediaKind.Series && (request.season == null || request.episode == null)) {\n            loading?.update(\"Öffne ${match.response.apiName} …\")\n            loading?.dismiss()\n            openResolvedDetails(activity, match.response)\n            rememberLastProvider(activity, request, match.response.apiName)\n            return\n        }\n\n        ioSafe {\n            loading?.update(\"Stream wird über ${match.response.apiName} aufgelöst …\")\n            val prepared = preparePlayback(match.response, request)\n            if (loading?.isCancelled() == true) return@ioSafe\n            if (prepared != null && hasPlayableLinks(prepared)) {\n                if (loading?.isCancelled() == true) return@ioSafe\n                loading?.update(\"Wiedergabe wird gestartet …\")\n                loading?.dismiss()\n                launchPreparedPlayback(activity, prepared)\n                rememberLastProvider(activity, request, prepared.providerName)\n            } else {\n                loading?.dismiss()\n                main { showNoPlayableLinkDialog(activity, request, match.response.apiName) }\n            }\n        }\n    }""",
    )

    require_text(
        bridge_target,
        "ILauncherBridgeLoading.show(activity",
        "CloudStream bridge loading surface",
    )
    require_text(
        bridge_target,
        "Stream wird über ${prepared.providerName} aufgelöst",
        "CloudStream bridge link-resolution loading phase",
    )

    # 3) Let MainActivity consume cloudstreamplay://v1 before the raw cloudstreamplayer URL path.
    main_activity = root / "app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
    replace_once(
        main_activity,
        """                    } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {""",
        """                    } else if (safeURI(str)?.scheme == ILauncherDirectPlay.SCHEME) {\n                        return ILauncherDirectPlay.handle(this, str)\n                    } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {""",
    )

    # 4) Export only the explicit VIEW entry through the existing AccountSelectActivity gate.
    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(
        manifest,
        """            <!-- Allow searching with intents: cloudstreamsearch://Your%20Name -->\n            <intent-filter>""",
        """            <!-- I Launcher direct provider/playback handoff. -->\n            <intent-filter>\n                <action android:name=\"android.intent.action.VIEW\" />\n\n                <category android:name=\"android.intent.category.DEFAULT\" />\n                <category android:name=\"android.intent.category.BROWSABLE\" />\n\n                <data android:scheme=\"cloudstreamplay\" android:host=\"v1\" />\n            </intent-filter>\n\n            <!-- Allow searching with intents: cloudstreamsearch://Your%20Name -->\n            <intent-filter>""",
    )

    # 5) The uploaded APK was signed by an ephemeral Android debug key. Its private key is not
    # recoverable from the APK. For reproducible future bridge updates, make prereleaseDebug use
    # the explicitly supplied stable development signing config when CI provides one.
    build_gradle = root / "app/build.gradle.kts"
    replace_once(
        build_gradle,
        """        debug {\n            isDebuggable = true\n            applicationIdSuffix = \".debug\"\n            proguardFiles(""",
        """        debug {\n            isDebuggable = true\n            applicationIdSuffix = \".debug\"\n            if (signingConfigs.names.contains(\"prerelease\")) {\n                signingConfig = signingConfigs.getByName(\"prerelease\")\n            }\n            proguardFiles(""",
    )

    print(f"Applied I Launcher CloudStream bridge patch on upstream {BASE_COMMIT}")


if __name__ == "__main__":
    main()
