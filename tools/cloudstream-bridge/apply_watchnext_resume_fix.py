#!/usr/bin/env python3
"""Keep bridge-only resume requests until the exact native player dispatch."""
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
    activity = base / "MainActivity.kt"
    replace_once(activity, """                        val targetActivity = this
                        val launchToken = ILauncherBridgeNavigation.beginExternalLaunch(targetActivity)
                        ioSafe {
                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()
                            val resumeWatchingCard =
                                ILauncherWatchNextIntent.resolve(resumeWatching, target)
                                    ?: return@ioSafe
                            ILauncherBridgeNavigation.navigateExternal(targetActivity, launchToken) {
                                targetActivity.loadSearchResult(
                                    resumeWatchingCard,
                                    com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP,
                                    resumeWatchingCard.id
                                )
                            }
                        }""", """                        ILauncherWatchNextResume.start(this, target)""")
    context = base / "utils/AppContextUtils.kt"
    replace_once(context, """    fun Activity?.loadSearchResult(
        card: SearchResponse,
        startAction: Int = 0,
        startValue: Int? = null,
    )""", """    fun Activity?.loadSearchResult(
        card: SearchResponse,
        startAction: Int = 0,
        startValue: Int? = null,
        bridgeResumeTicket: String? = null,
    )""")
    replace_once(context, "ResultFragment.newInstance(card, startAction, startValue)", "ResultFragment.newInstance(card, startAction, startValue, bridgeResumeTicket)")
    fragment = base / "ui/result/ResultFragment.kt"
    replace_once(fragment, """    fun newInstance(
        card: SearchResponse, startAction: Int = 0, startValue: Int? = null
    ): Bundle""", """    fun newInstance(
        card: SearchResponse, startAction: Int = 0, startValue: Int? = null,
        bridgeResumeTicket: String? = null,
    ): Bundle""")
    replace_once(fragment, """            if (startValue != null)
                putInt(START_VALUE_BUNDLE, startValue)""", """            if (startValue != null)
                putInt(START_VALUE_BUNDLE, startValue)
            bridgeResumeTicket?.let { putString("ilauncher_resume_ticket", it) }""")
    replace_once(fragment, """        val start = startAction?.let { action ->
            val startValue = arguments?.getInt(START_VALUE_BUNDLE)
            val resumeEpisode = arguments?.getInt(EPISODE_BUNDLE)
            val resumeSeason = arguments?.getInt(SEASON_BUNDLE)

            arguments?.remove(START_VALUE_BUNDLE)
            arguments?.remove(START_ACTION_BUNDLE)
            AutoResume(
                startAction = action,
                id = startValue,
                episode = resumeEpisode,
                season = resumeSeason
            )
        }""", """        val ticket = arguments?.getString("ilauncher_resume_ticket")
        val bridgePending = ticket != null &&
            com.lagradost.cloudstream3.ILauncherWatchNextResume.isPending(ticket)
        val start = if (ticket != null && !bridgePending) {
            arguments?.remove(START_VALUE_BUNDLE)
            arguments?.remove(START_ACTION_BUNDLE)
            null
        } else startAction?.let { action ->
            val startValue = arguments?.takeIf { it.containsKey(START_VALUE_BUNDLE) }?.getInt(START_VALUE_BUNDLE)
            val resumeEpisode = arguments?.takeIf { it.containsKey(EPISODE_BUNDLE) }?.getInt(EPISODE_BUNDLE)
            val resumeSeason = arguments?.takeIf { it.containsKey(SEASON_BUNDLE) }?.getInt(SEASON_BUNDLE)
            // Only bridge requests survive provider reloads. Other CloudStream entry points
            // retain their original one-read behavior.
            if (!bridgePending) {
                arguments?.remove(START_VALUE_BUNDLE)
                arguments?.remove(START_ACTION_BUNDLE)
            }
            AutoResume(
                startAction = action,
                id = startValue,
                episode = resumeEpisode,
                season = resumeSeason,
                bridgeResumeTicket = ticket,
            )
        }""")
    vm = base / "ui/result/ResultViewModel2.kt"
    replace_once(vm, """data class AutoResume(
    val season: Int?,
    val episode: Int?,
    val id: Int?,
    val startAction: Int,
)""", """data class AutoResume(
    val season: Int?,
    val episode: Int?,
    val id: Int?,
    val startAction: Int,
    val bridgeResumeTicket: String? = null,
)""")
    replace_once(vm, """            if (autostart == null || activity == null) return@launchSafe

            when (autostart.startAction)""", """            if (autostart == null || activity == null) return@launchSafe

            autostart.bridgeResumeTicket?.let { ticket ->
                val resume = com.lagradost.cloudstream3.ILauncherWatchNextResume
                if (!resume.isPending(ticket) || activity.isFinishing || activity.isDestroyed) return@launchSafe
                val all = currentEpisodes.values.flatten()
                val selected = resume.select(
                    all.map { com.lagradost.cloudstream3.ILauncherWatchNextResume.Coordinate(it.id, it.season, it.episode) },
                    autostart.id, autostart.season, autostart.episode
                )
                val episode = all.firstOrNull { it.id == selected }
                if (episode == null) {
                    Log.w("ILauncherWatchNextResume", "autostart target missing id=${autostart.id} season=${autostart.season} episode=${autostart.episode} catalogue=${all.size}")
                    return@launchSafe
                }
                val action = getPlayerAction(activity)
                if (action == ACTION_PLAY_EPISODE_IN_PLAYER && generator?.videos?.any { it.id == episode.id } != true) {
                    Log.w("ILauncherWatchNextResume", "autostart generator not ready id=${episode.id}")
                    return@launchSafe
                }
                if (!resume.claim(ticket)) return@launchSafe
                Log.i("ILauncherWatchNextResume", "autostart dispatch id=${episode.id} season=${episode.season} episode=${episode.episode} action=$action")
                handleEpisodeClickEvent(EpisodeClickEvent(action, episode))
                return@launchSafe
            }

            when (autostart.startAction)""")
    here = Path(__file__).parent
    shutil.copyfile(here / "ILauncherWatchNextResume.kt", base / "ILauncherWatchNextResume.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherWatchNextResumeTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(here / test.name, test)

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
