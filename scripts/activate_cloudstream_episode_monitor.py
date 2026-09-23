from pathlib import Path


def patch(path: str, before: str, after: str) -> None:
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    occurrences = text.count(before)
    if occurrences != 1:
        raise RuntimeError(f'{path}: expected one anchor; got {occurrences}: {before[:85]}')
    file.write_text(text.replace(before, after, 1), encoding='utf-8')

monitor = 'tools/cloudstream-bridge/ILauncherNextEpisodeMonitor.kt'
patch(monitor,
      '            if (previous != null && (previous.finishedSeason > season ||\n                    previous.finishedSeason == season && previous.finishedEpisode > episode.episode)) return',
      '            if (previous != null && (previous.finishedSeason > season ||\n                    previous.finishedSeason == season && previous.finishedEpisode >= episode.episode)) return')
patch(monitor,
      '                val series = (result as? Resource.Success)?.value as? TvSeriesLoadResponse ?: return@forEach',
      '                val series = (result as? Resource.Success<*>)?.value as? TvSeriesLoadResponse ?: return@forEach')
patch(monitor,
      '                val ((season, episode), source) = candidate',
      '                val coordinates = candidate.first\n                val season = coordinates.first\n                val episode = coordinates.second\n                val source = candidate.second')
patch(monitor,
      'import kotlinx.coroutines.sync.withLock\n', '')
patch(monitor,
      'import com.lagradost.cloudstream3.utils.Coroutines.ioSafe\n', '')
patch(monitor,
      'import com.lagradost.cloudstream3.utils.DataStoreHelper\n', '')
patch(monitor,
      '    private const val TAG = "ILauncherNextEpisode"',
      '    private const val TAG = "ILauncherNextEpisode"') if False else None

workflow = '.github/workflows/cloudstream-bridge.yml'
patch(workflow,
      '          python i-launcher/tools/cloudstream-bridge/apply_legacy_watchnext_fix.py cloudstream\n          python i-launcher/tools/cloudstream-bridge/apply_dev_version.py cloudstream',
      '          python i-launcher/tools/cloudstream-bridge/apply_legacy_watchnext_fix.py cloudstream\n          python i-launcher/tools/cloudstream-bridge/apply_next_episode_monitor.py cloudstream\n          python i-launcher/tools/cloudstream-bridge/apply_dev_version.py cloudstream')

Path('docs/CLOUDSTREAM_EPISODE_AVAILABILITY.md').write_text('''# CloudStream: next/new episode and Android TV Watch Next

Branch: `agent/cloudstream-direct-play`. The CloudStream APK is generated from the pinned upstream checkout and versioned bridge patch sources under `tools/cloudstream-bridge/`; this is not a standalone CloudStream source tree.

- A finished last available episode is remembered with its **source plugin**, exact series URL, season, episode and CloudStream parent ID. An unchanged repeated player-exit event does not reset a previously observed absence.
- When the plugin runtime is active, at most six tracked series are checked per run and checks are throttled to six hours. The provider's own season/episode list and `Episode.date` provide candidate coordinates and possible scheduled releases. A future timestamp cannot itself mark an episode playable.
- An actually playable stream is confirmed by `RepoLinkGenerator.generateLinks` with a short timeout, not by a TMDB date. Failed provider loading or link extraction does not prove a new episode is absent.
- A first observed playable successor is `NEXT`. A successor observed after a **previous successful series-catalogue check without a released next episode** is `NEW`. Both use the provider-specific direct-play bridge URI. An unfinished episode is `CONTINUE` with playback progress.
- The resulting entries are emitted through Android TvProvider Watch Next inside the original CloudStream transaction, so the resume cleanup does not immediately erase announcements. I Launcher only reads those platform rows and shows existing badges.

Limitation: a CloudStream extension's `Episode.date` is not uniformly available and may represent a planned broadcast rather than an available stream. Some extensions show future dates only as formatted UI titles. This implementation does not parse arbitrary titles as reliable dates. Monitoring currently executes on CloudStream's active runtime / home refresh / normal player exit, **not while CloudStream's process is fully stopped**. Background checks would need an independently initialized extension runtime and explicit battery/network controls.

Device testing: finish an episode with no current successor, revisit CloudStream after its provider has released the next episode, and verify the Watch Next card in I Launcher. Tests and CI do not by themselves validate individual plugins such as SerienStream and Moflix.
''', encoding='utf-8')
print('CloudStream episode monitor source + workflow prepared')
