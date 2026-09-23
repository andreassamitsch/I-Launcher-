from pathlib import Path


def patch(path, before, after):
    p = Path(path)
    content = p.read_text(encoding='utf-8')
    count = content.count(before)
    if count != 1:
        raise RuntimeError(f'{path}: expected one anchor, found {count}: {before[:85]}')
    p.write_text(content.replace(before, after, 1), encoding='utf-8')

monitor = 'tools/cloudstream-bridge/ILauncherNextEpisodeMonitor.kt'
patch(monitor,
    'import com.lagradost.cloudstream3.utils.DataStoreHelper.getKey',
    'import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey')
bridge = 'tools/cloudstream-bridge/apply_next_episode_monitor.py'
patch(bridge,
    '                it.parentId == episode.parentId && isLaterEpisode(it, episode)\n            }\n            updateResumePointer(',
    '                it.parentId == episode.parentId && isLaterEpisode(it, episode) &&\n                    !ILauncherNextEpisodeMonitor.isFuture(it.airDate, System.currentTimeMillis())\n            }\n            updateResumePointer(')
patch(bridge,
    "    replace_once(sync,\n        '''        appContext.addProgramsToContinueWatching(resumeWatching)",
    """    replace_once(sync,
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
        '''        appContext.addProgramsToContinueWatching(resumeWatching)""")
print('Fixed CloudStream cache getKey import and future episode resume handling')
