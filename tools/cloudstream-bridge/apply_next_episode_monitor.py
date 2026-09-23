#!/usr/bin/env python3
"""Apply the CloudStream-owned new episode bridge only after all prior bridge patches."""
from pathlib import Path
import shutil
import sys


def replace_once(file: Path, before: str, after: str) -> None:
    content = file.read_text(encoding='utf-8')
    count = content.count(before)
    if count != 1:
        raise RuntimeError(f'{file}: expected one anchor, found {count}: {before[:110]}')
    file.write_text(content.replace(before, after, 1), encoding='utf-8')


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit('usage: apply_next_episode_monitor.py <cloudstream-source-root>')
    root = Path(sys.argv[1]).resolve()
    base = root / 'app/src/main/java/com/lagradost/cloudstream3'
    if not (base / 'MainActivity.kt').is_file():
        raise RuntimeError('CloudStream source root is missing')

    monitor_source = Path(__file__).with_name('ILauncherNextEpisodeMonitor.kt')
    monitor_target = base / 'ui/player/ILauncherNextEpisodeMonitor.kt'
    shutil.copyfile(monitor_source, monitor_target)
    test_source = Path(__file__).with_name('ILauncherNextEpisodeMonitorTest.kt')
    test_target = root / 'app/src/test/java/com/lagradost/cloudstream3/ui/player/ILauncherNextEpisodeMonitorTest.kt'
    test_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(test_source, test_target)

    sync = base / 'ui/player/ILauncherWatchNextSync.kt'
    replace_once(sync,
        '''        if (shouldPersistPosition && episode != null) {
            updateResumePointer(
                episode = episode,
                nextEpisode = nextMeta as? ResultEpisode,
                position = position!!,
                duration = duration!!,
            )
        }

        publish(context, reason)''',
        '''        if (shouldPersistPosition && episode != null) {
            val nextEpisode = (nextMeta as? ResultEpisode)?.takeIf {
                it.parentId == episode.parentId && isLaterEpisode(it, episode) &&
                    !ILauncherNextEpisodeMonitor.isFuture(it.airDate, System.currentTimeMillis())
            }
            updateResumePointer(
                episode = episode,
                nextEpisode = nextEpisode,
                position = position!!,
                duration = duration!!,
            )
            val isFinished = DataStoreHelper.getVideoWatchState(episode.id) == VideoWatchState.Watched ||
                position * 100L / duration >= NEXT_WATCH_EPISODE_PERCENTAGE
            if (isFinished && nextEpisode == null) {
                ILauncherNextEpisodeMonitor.recordCompleted(context, episode)
            }
        }

        publish(context, reason)''')
    replace_once(sync,
        '''                    } else {
                        DataStoreHelper.removeLastWatched(episode.parentId)
                    }
                }

                publishNow(context, "markedWatched")''',
        '''                    } else {
                        DataStoreHelper.removeLastWatched(episode.parentId)
                        ILauncherNextEpisodeMonitor.recordCompleted(context, episode)
                    }
                }

                publishNow(context, "markedWatched")''')
    replace_once(sync,
        '''                    val next = nextEpisode?.takeIf {
                        it.parentId == episode.parentId && isLaterEpisode(it, episode)
                    }
                    if (next != null) {''',
        '''                    val next = nextEpisode?.takeIf {
                        it.parentId == episode.parentId && isLaterEpisode(it, episode) &&
                            !ILauncherNextEpisodeMonitor.isFuture(it.airDate, System.currentTimeMillis())
                    }
                    if (next != null) {''')
    replace_once(sync,
        '''        appContext.addProgramsToContinueWatching(resumeWatching)
        Log.i(TAG, "flush reason=$reason count=${resumeWatching.size}")''',
        '''        appContext.addProgramsToContinueWatching(resumeWatching)
        ILauncherNextEpisodeMonitor.refresh(appContext)
        Log.i(TAG, "flush reason=$reason count=${resumeWatching.size}")''')

    home = base / 'ui/home/HomeViewModel.kt'
    replace_once(home,
        'import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching',
        'import com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor\nimport com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching')
    replace_once(home,
        '''                activity?.addProgramsToContinueWatching(resumeWatchingResult)
            }
        }''',
        '''                activity?.addProgramsToContinueWatching(resumeWatchingResult)
                activity?.let { ILauncherNextEpisodeMonitor.refresh(it.applicationContext) }
            }
        }''')

    appcontext = base / 'utils/AppContextUtils.kt'
    replace_once(appcontext,
        'import com.lagradost.cloudstream3.ui.player.SubtitleData',
        'import com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor\nimport com.lagradost.cloudstream3.ui.player.SubtitleData')
    replace_once(appcontext,
        '''            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(title)''',
        '''            .setWatchNextType(if (isSeries && card.watchPos == null && card.episode != null) {
                TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            } else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(title)''')
    replace_once(appcontext,
        '''        if (isSeries)
            card.episode?.let {
                builder.setEpisodeNumber(it)
            }

        return builder.build()''',
        '''        if (isSeries) {
            card.episode?.let { builder.setEpisodeNumber(it) }
            card.season?.let { builder.setSeasonNumber(it) }
        }

        return builder.build()''')
    replace_once(appcontext,
        '''            val allOldPrograms = getAllWatchNextPrograms(context) - currentProgramIds

            // Ensures synced watch next progress by deleting all old programs.''',
        '''            // These are CloudStream-owned announcements. Keep their provider-specific links
            // in the same write transaction or the normal resume cleanup would immediately delete them.
            val upcomingProgramIds = ILauncherNextEpisodeMonitor.snapshot(context).mapNotNull { (key, upcoming) ->
                try {
                    val (_, existingId) = getWatchNextProgramByVideoId(key, context)
                    if (existingId != null) {
                        PreviewChannelHelper(context).updateWatchNextProgram(upcoming, existingId)
                        existingId
                    } else {
                        PreviewChannelHelper(context).publishWatchNextProgram(upcoming)
                    }
                } catch (error: Exception) {
                    logError(error)
                    null
                }
            }.toSet()
            val allOldPrograms = getAllWatchNextPrograms(context) - currentProgramIds - upcomingProgramIds

            // Ensures synced watch next progress by deleting all old programs.''')

    bridge = base / 'ILauncherDirectPlay.kt'
    replace_once(bridge,
        '''        val providerSelection: ProviderSelection,
    )''',
        '''        val providerSelection: ProviderSelection,
        val providerName: String? = null,
    )''')
    replace_once(bridge,
        '''            providerSelection = when (query["selection"]?.lowercase()) {
                "choose" -> ProviderSelection.Choose
                else -> ProviderSelection.Automatic
            },''',
        '''            providerSelection = when (query["selection"]?.lowercase()) {
                "choose" -> ProviderSelection.Choose
                else -> ProviderSelection.Automatic
            },
            providerName = query["provider"]?.trim()?.takeIf(String::isNotBlank),''')
    replace_once(bridge,
        '''        if (providers.isEmpty()) return emptyList()
        val byName = providers.associateBy { it.name }''',
        '''        if (providers.isEmpty()) return emptyList()
        // The new-episode card is confirmed against a specific extension. Do not silently
        // switch the playback target to a different provider with another episode catalogue.
        request.providerName?.let { pinned -> return providers.filter { it.name == pinned } }
        val byName = providers.associateBy { it.name }''')
    print('CloudStream Watch Next NEXT/NEW monitor patched')


if __name__ == '__main__':
    main()
