#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"Expected exactly one match in {path}: found {count}\n--- needle ---\n{old}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_expected(path: Path, old: str, new: str, expected: int) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise RuntimeError(
            f"Expected {expected} matches in {path}: found {count}\n--- needle ---\n{old}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


def require_text(path: Path, needle: str, description: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise RuntimeError(f"Missing required {description} in {path}:\n{needle}")


def copy_sibling(name: str, target: Path) -> None:
    source = Path(__file__).with_name(name)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_behavior_fixes.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app/src/main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    # Supplemental bridge sources. The base patch is intentionally kept untouched so its
    # previously device-tested Watch Next invariants remain independently reproducible.
    root_package = root / "app/src/main/java/com/lagradost/cloudstream3"
    copy_sibling("ILauncherWatchNextIntent.kt", root_package / "ILauncherWatchNextIntent.kt")
    copy_sibling("ILauncherBridgeNavigation.kt", root_package / "ILauncherBridgeNavigation.kt")
    copy_sibling("ILauncherBridgePreferences.kt", root_package / "ILauncherBridgePreferences.kt")
    copy_sibling("ILauncherBridgeUpdater.kt", root_package / "ILauncherBridgeUpdater.kt")
    copy_sibling(
        "ILauncherBridgeBehaviorTest.kt",
        root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherBridgeBehaviorTest.kt",
    )

    bridge = root_package / "ILauncherDirectPlay.kt"

    # Direct playback must replace an old player destination instead of stacking a new player on
    # top of a stale E4/E5 session in MainActivity's singleTask navigation stack.
    replace_once(
        bridge,
        """    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback) {\n        main {\n            activity.navigate(\n                R.id.global_to_navigation_player,\n                GeneratorPlayer.newInstance(prepared.generator, prepared.index, prepared.syncData),\n            )\n        }\n    }""",
        """    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback) {\n        ILauncherBridgeNavigation.replacePlayer(\n            activity,\n            GeneratorPlayer.newInstance(prepared.generator, prepared.index, prepared.syncData),\n        )\n    }""",
    )

    # Movie and episodic content use different user-defined provider orders. Episodes share the
    # series order; the per-media last-successful provider still remains more specific and wins.
    replace_once(
        bridge,
        "preferredOrder = readProviderOrder(activity),",
        "preferredOrder = ILauncherBridgePreferences.readProviderOrder(activity, request.kind),",
    )
    replace_expected(
        bridge,
        "showProviderPriorityDialog(activity, allProviders)",
        "showProviderPriorityDialog(activity, allProviders, request.kind)",
        2,
    )
    replace_once(
        bridge,
        "private fun showProviderPriorityDialog(activity: FragmentActivity, providers: List<MainAPI>) {",
        "private fun showProviderPriorityDialog(\n        activity: FragmentActivity,\n        providers: List<MainAPI>,\n        kind: MediaKind,\n    ) {",
    )
    replace_once(
        bridge,
        "val ordered = mergeProviderOrder(activeNames, readProviderOrder(activity)).toMutableList()",
        "val ordered = mergeProviderOrder(\n            activeNames,\n            ILauncherBridgePreferences.readProviderOrder(activity, kind),\n        ).toMutableList()",
    )
    replace_once(
        bridge,
        ".setTitle(\"Provider-Priorität\")",
        ".setTitle(\"Provider-Priorität · ${ILauncherBridgePreferences.kindLabel(kind)}\")",
    )
    replace_once(
        bridge,
        ".setPositiveButton(\"Fertig\") { _, _ -> saveProviderOrder(activity, ordered) }",
        ".setPositiveButton(\"Fertig\") { _, _ ->\n                ILauncherBridgePreferences.saveProviderOrder(activity, kind, ordered)\n            }",
    )

    # Publish an exact, stable Watch Next target. Android can now show S1E7 and later hand exactly
    # S1E7 back even if CloudStream's local resume pointer has meanwhile moved to an older episode.
    app_context = root / "app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt"
    replace_once(
        app_context,
        "import com.lagradost.cloudstream3.HomePageList\n",
        "import com.lagradost.cloudstream3.HomePageList\nimport com.lagradost.cloudstream3.ILauncherWatchNextIntent\n",
    )
    replace_once(
        app_context,
        """            .setIntentUri((card.id?.let {\n                \"$APP_STRING_RESUME_WATCHING://$it\"\n            } ?: card.url).toUri())""",
        """            .setIntentUri(\n                ILauncherWatchNextIntent.build(\n                    parentId = card.parentId,\n                    episodeId = card.id,\n                    season = card.season,\n                    episode = card.episode,\n                    fallbackUrl = card.url,\n                ).toUri()\n            )""",
    )

    main_activity = root / "app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
    replace_once(
        main_activity,
        """                    } else if (safeURI(str)?.scheme == APP_STRING_RESUME_WATCHING) {\n                        val id =\n                            str.substringAfter(\"$APP_STRING_RESUME_WATCHING://\").toIntOrNull()\n                                ?: return false\n                        ioSafe {\n                            val resumeWatchingCard =\n                                HomeViewModel.getResumeWatching()?.firstOrNull { it.id == id }\n                                    ?: return@ioSafe\n                            activity.loadSearchResult(\n                                resumeWatchingCard,\n                                START_ACTION_RESUME_LATEST\n                            )\n                        }""",
        """                    } else if (safeURI(str)?.scheme == APP_STRING_RESUME_WATCHING) {\n                        val target = ILauncherWatchNextIntent.parse(str) ?: return false\n                        val targetActivity = this\n                        ioSafe {\n                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()\n                            val resumeWatchingCard =\n                                ILauncherWatchNextIntent.resolve(resumeWatching, target)\n                                    ?: return@ioSafe\n                            main {\n                                ILauncherBridgeNavigation.clearExistingPlayer(targetActivity)\n                                targetActivity.loadSearchResult(\n                                    resumeWatchingCard,\n                                    START_ACTION_RESUME_LATEST\n                                )\n                            }\n                        }""",
    )

    # Reuse CloudStream's singleTask MainActivity but check the bridge-specific development channel
    # once per process. Manual checks remain available from General -> I Launcher Bridge.
    replace_once(
        main_activity,
        """        setActivityInstance(this)\n        try {""",
        """        setActivityInstance(this)\n        ILauncherBridgeUpdater.checkForUpdates(this, automatic = true)\n        try {""",
    )

    # Add bridge-owned preferences directly to CloudStream's General settings.
    settings_general = root / "app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt"
    replace_once(
        settings_general,
        "import com.lagradost.cloudstream3.CloudStreamApp\n",
        "import com.lagradost.cloudstream3.CloudStreamApp\nimport com.lagradost.cloudstream3.ILauncherBridgePreferences\n",
    )
    replace_once(
        settings_general,
        """        setPreferencesFromResource(R.xml.settings_general, rootKey)\n        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())""",
        """        setPreferencesFromResource(R.xml.settings_general, rootKey)\n        ILauncherBridgePreferences.install(this)\n        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())""",
    )

    # CloudStream's normal "mark as watched" only changes VideoWatchState. If that episode is the
    # current series resume target, advance/remove the resume pointer immediately and republish the
    # TvProvider row so the launcher no longer keeps a stale episode around.
    result_vm = root / "app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    replace_once(
        result_vm,
        "import com.lagradost.cloudstream3.ui.player.GeneratorPlayer\n",
        "import com.lagradost.cloudstream3.ui.player.GeneratorPlayer\nimport com.lagradost.cloudstream3.ui.player.ILauncherWatchNextSync\n",
    )
    replace_once(
        result_vm,
        """            ACTION_MARK_AS_WATCHED -> {\n                val isWatched =\n                    getVideoWatchState(click.data.id) == VideoWatchState.Watched\n                if (isWatched) {\n                    setVideoWatchState(click.data.id, VideoWatchState.None)\n                } else {\n                    setVideoWatchState(click.data.id, VideoWatchState.Watched)\n                }\n                // Kinda dirty to reload all episodes :(\n                reloadEpisodes()\n            }""",
        """            ACTION_MARK_AS_WATCHED -> {\n                val isWatched =\n                    getVideoWatchState(click.data.id) == VideoWatchState.Watched\n                val newState = if (isWatched) VideoWatchState.None else VideoWatchState.Watched\n                setVideoWatchState(click.data.id, newState)\n                if (newState == VideoWatchState.Watched) {\n                    val currentSeason = click.data.season ?: click.data.seasonIndex ?: 0\n                    val nextEpisode = generator?.videos\n                        ?.filterIsInstance<ResultEpisode>()\n                        ?.filter { candidate ->\n                            candidate.parentId == click.data.parentId &&\n                                ((candidate.season ?: candidate.seasonIndex ?: 0) > currentSeason ||\n                                    ((candidate.season ?: candidate.seasonIndex ?: 0) == currentSeason &&\n                                        candidate.episode > click.data.episode))\n                        }\n                        ?.minWithOrNull(\n                            compareBy<ResultEpisode> { it.season ?: it.seasonIndex ?: 0 }\n                                .thenBy { it.episode }\n                        )\n                    ILauncherWatchNextSync.onMarkedWatched(context, click.data, nextEpisode)\n                }\n                // Kinda dirty to reload all episodes :(\n                reloadEpisodes()\n            }""",
    )

    # Final guards make CI fail loudly instead of silently shipping a partially patched bridge.
    require_text(bridge, "ILauncherBridgeNavigation.replacePlayer", "player replacement handoff")
    require_text(bridge, "readProviderOrder(activity, request.kind)", "media-specific provider order")
    require_text(app_context, "ILauncherWatchNextIntent.build", "exact Watch Next intent")
    require_text(main_activity, "ILauncherWatchNextIntent.resolve", "exact Watch Next resume resolution")
    require_text(main_activity, "ILauncherBridgeUpdater.checkForUpdates", "bridge updater auto-check")
    require_text(settings_general, "ILauncherBridgePreferences.install(this)", "bridge settings")
    require_text(result_vm, "ILauncherWatchNextSync.onMarkedWatched", "watched-state resume sync")


if __name__ == "__main__":
    main()
