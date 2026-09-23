#!/usr/bin/env python3
"""Apply after apply_next_episode_monitor.py and before the bridge APK is built."""
from pathlib import Path
import shutil
import sys


def patch(path: Path, before: str, after: str) -> None:
    content = path.read_text(encoding='utf-8')
    count = content.count(before)
    if count != 1:
        raise RuntimeError(f'{path}: expected 1 anchor, found {count}: {before[:120]!r}')
    path.write_text(content.replace(before, after, 1), encoding='utf-8')


def main(root: Path) -> None:
    base = root / 'app/src/main/java/com/lagradost/cloudstream3'
    if not (base / 'ui/player/ILauncherNextEpisodeMonitor.kt').exists():
        raise RuntimeError('The next episode monitor must be installed before the availability guard')

    guard_source = Path(__file__).with_name('ILauncherPendingResumeGuard.kt')
    shutil.copyfile(guard_source, base / 'ui/player/ILauncherPendingResumeGuard.kt')

    # CloudStream's regular per-tick progress writer ran BEFORE the bridge's player-exit hook.
    # Never auto-promote an unverified ResultEpisode to a new resume target; the monitor is the
    # only component allowed to publish NEXT/NEW after its provider/link and air-date check.
    store = base / 'utils/DataStoreHelper.kt'
    patch(store,
        '        val resumeMeta = if (nextEp) nextEpisode else currentEpisode',
        '''        // Episode data in the catalogue may only be a future announcement. The release
        // monitor validates both its timestamp and real playback links before publishing it.
        val resumeMeta = if (nextEp) {
            if (currentEpisode is ResultEpisode) null else nextEpisode
        } else currentEpisode''')

    sync = base / 'ui/player/ILauncherWatchNextSync.kt'
    patch(sync,
        '''            val nextEpisode = (nextMeta as? ResultEpisode)?.takeIf {
                it.parentId == episode.parentId && isLaterEpisode(it, episode) &&
                    !ILauncherNextEpisodeMonitor.isFuture(it.airDate, System.currentTimeMillis())
            }''',
        '''            // The catalogue listing itself cannot establish playback availability. Keep
            // the finished episode out of resume until the monitor verifies its successor.
            val nextEpisode: ResultEpisode? = null''')
    patch(sync,
        '''                    val next = nextEpisode?.takeIf {
                        it.parentId == episode.parentId && isLaterEpisode(it, episode) &&
                            !ILauncherNextEpisodeMonitor.isFuture(it.airDate, System.currentTimeMillis())
                    }''',
        '''                    // Marking an episode watched must not jump to an unverified announcement.
                    val next: ResultEpisode? = null''')

    # Direct-play requests coming from old Android TvProvider rows must not start a future episode.
    direct = base / 'ILauncherDirectPlay.kt'
    patch(direct,
        '''        val selectedIndex = findEpisodeIndex(series, requestedSeason, requestedEpisode)
        if (selectedIndex < 0) return null''',
        '''        val selectedIndex = findEpisodeIndex(series, requestedSeason, requestedEpisode)
        if (selectedIndex < 0) return null
        if (ILauncherNextEpisodeMonitor.isFuture(series.episodes[selectedIndex].date, System.currentTimeMillis())) {
            return null
        }''')
    patch(direct,
        'import com.lagradost.cloudstream3.ui.player.GeneratorPlayer',
        'import com.lagradost.cloudstream3.ui.player.GeneratorPlayer\nimport com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor')

    # Migrate previously saved, unplayed, future episodes on the TV. Do not touch episodes with
    # actual playback progress. If an old provider cannot be reached, temporarily suppress the
    # unverified card rather than corrupting its saved resume pointer.
    home = base / 'ui/home/HomeViewModel.kt'
    patch(home,
        'import com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor',
        'import com.lagradost.cloudstream3.ui.player.ILauncherNextEpisodeMonitor\nimport com.lagradost.cloudstream3.ui.player.ILauncherPendingResumeGuard')
    patch(home,
        '''            return resumeWatchingResult
        }
    }''',
        '''            val appContext = context
            return if (appContext == null || resumeWatchingResult == null) {
                resumeWatchingResult
            } else ILauncherPendingResumeGuard.filter(appContext, resumeWatchingResult)
        }
    }''')
    print('Applied guarded next-episode persistence and legacy future-resume migration')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: apply_episode_availability_guard.py <cloudstream-source-root>')
    main(Path(sys.argv[1]).resolve())
